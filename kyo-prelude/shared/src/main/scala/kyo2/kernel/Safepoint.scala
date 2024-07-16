package kyo2.kernel

import internal.*
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import kyo2.isNull
import kyo2.kernel.Safepoint.*
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

final class Safepoint(initDepth: Int, initInterceptor: Interceptor, initState: State) extends Trace.Owner:
    import Safepoint.State

    private val owner               = Thread.currentThread()
    private[kernel] var depth       = initDepth
    private[kernel] var interceptor = initInterceptor

    private def enter(frame: Frame, value: Any): Int =
        if (Thread.currentThread eq owner) && depth < maxStackDepth &&
            (isNull(interceptor) || interceptor.enter(frame, value))
        then
            pushFrame(frame)
            val depth = this.depth
            this.depth = depth + 1
            depth + 1
        else
            -1
        end if
    end enter

    private def exit(depth: Int): Unit =
        this.depth = depth - 1
        if !isNull(interceptor) then
            interceptor.exit()
    end exit

    private[kernel] def save(context: Context) =
        State(saveTrace(), context)
end Safepoint

object Safepoint:

    implicit def get: Safepoint = local.get()

    abstract private[kyo2] class Interceptor:
        def addEnsure(f: () => Unit): Unit
        def removeEnsure(f: () => Unit): Unit
        def enter(frame: Frame, value: Any): Boolean
        def exit(): Unit
    end Interceptor

    private[kyo2] inline def immediate[A, S](p: Interceptor)(inline v: => A < S)(
        using safepoint: Safepoint
    ): A < S =
        val prev = safepoint.interceptor
        val np =
            if isNull(prev) || (prev eq p) then p
            else
                new Interceptor:
                    override def addEnsure(f: () => Unit): Unit    = p.addEnsure(f)
                    override def removeEnsure(f: () => Unit): Unit = p.removeEnsure(f)
                    def enter(frame: Frame, value: Any) =
                        p.enter(frame, value) && prev.enter(frame, value)
                    def exit() =
                        p.exit()
                        prev.exit()
        safepoint.interceptor = np
        try v
        finally safepoint.interceptor = prev
    end immediate

    private[kyo2] inline def propagating[A, S](p: Interceptor)(inline v: => A < S)(
        using
        inline safepoint: Safepoint,
        inline _frame: Frame
    ): A < S =
        def loop(v: A < S): A < S =
            v match
                case <(kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked) =>
                    new KyoContinue[IX, OX, EX, Any, A, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint): A < S =
                            loop(immediate(p)(kyo(v, context)))
                case _ =>
                    v
        immediate(p)(loop(v))
    end propagating

    abstract private[kyo2] class Ensure extends AtomicBoolean with Function0[Unit]:
        def run: Unit
        final def apply(): Unit =
            if compareAndSet(false, true) then
                val safepoint = Safepoint.get
                val prev      = safepoint.interceptor
                safepoint.interceptor = null
                try run
                finally safepoint.interceptor = prev
    end Ensure

    private inline def ensuring[A](ensure: Ensure)(inline thunk: => A)(using safepoint: Safepoint): A =
        val interceptor = safepoint.interceptor
        if !isNull(interceptor) then interceptor.addEnsure(ensure)
        try thunk
        catch
            case ex if NonFatal(ex) =>
                ensure()
                throw ex
        end try
    end ensuring

    private[kyo2] inline def ensure[A, S](inline f: => Unit)(inline v: => A < S)(using safepoint: Safepoint, inline _frame: Frame): A < S =
        // ensures the function is called once even if an
        // interceptor executes it multiple times
        val ensure = new Ensure:
            def run: Unit = f

        def ensureLoop(v: A < S)(using safepoint: Safepoint): A < S =
            v match
                case <(kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked) =>
                    new KyoContinue[IX, OX, EX, Any, A, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint): A < S =
                            ensuring(ensure)(ensureLoop(kyo(v, context)))
                case _ =>
                    val interceptor = safepoint.interceptor
                    if !isNull(interceptor) then interceptor.removeEnsure(ensure)
                    ensure()
                    v
        ensuring(ensure)(ensureLoop(v))
    end ensure

    private[kernel] inline def eval[A](
        inline f: => A
    )(using inline frame: Frame): A =
        Safepoint.local.get().withNewTrace(f)

    private[kernel] inline def handle[V, A, S](value: V)(
        inline suspend: Safepoint ?=> A < S,
        inline continue: => A < S
    )(using inline frame: Frame, self: Safepoint): A < S =
        self.enter(frame, value) match
            case -1 =>
                Effect.defer(suspend)
            case depth =>
                try continue
                finally self.exit(depth)
    end handle

    private[kernel] inline def handle[A, B, S](value: Any)(
        inline eval: => A,
        inline continue: A => B < S,
        inline suspend: Safepoint ?=> B < S
    )(using inline frame: Frame, self: Safepoint): B < S =
        self.enter(frame, value) match
            case -1 =>
                Effect.defer(suspend)
            case depth =>
                val a =
                    try eval
                    finally self.exit(depth)
                continue(a)
        end match
    end handle

    def enrich(ex: Throwable)(using safepoint: Safepoint): Unit =
        safepoint.enrich(ex)

    import internal.*

    final class State(
        val trace: Trace,
        val context: Context
    )

    object State:
        val empty = State(Trace.init, Context.empty)

    private[kernel] val local = ThreadLocal.withInitial(() => Safepoint(0, null, State.empty))

end Safepoint
