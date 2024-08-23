package kyo.debug

import kyo.*
import kyo.Ansi.*
import kyo.kernel.Effect
import kyo.kernel.Safepoint
import scala.collection.mutable.LinkedHashMap
import scala.language.implicitConversions
import scala.quoted.*

object Debug:

    private inline def maxValueLength = 500

    def apply[A, S](v: => A < S)(using frame: Frame): A < S =
        Effect.catching {
            v.map { value =>
                println(frame.show)
                printValue(value)
                value
            }
        } { ex =>
            println(frame.show)
            printValue(ex)
            throw ex
        }
    end apply

    def trace[A, S](v: => A < S)(using Frame): A < S =
        val interceptor = new Safepoint.Interceptor:
            def enter(frame: Frame, value: Any): Boolean =
                printValue(value)
                println(frame.parse.show)
                true
            end enter
            def addEnsure(f: () => Unit): Unit    = ()
            def removeEnsure(f: () => Unit): Unit = ()

        Safepoint.propagating(interceptor) {
            Effect.catching {
                v.map { value =>
                    printValue(value)
                    value
                }
            } { ex =>
                printValue(ex)
                throw ex
            }
        }
    end trace

    def values(params: Param[?]*)(using frame: Frame): Unit =
        val tuples = LinkedHashMap(params.map(p => (p.code, p.value))*)
        val string = pprint(tuples).render.replaceFirst("LinkedHashMap", "Params")
        println(frame.parse.show)
        println(string)
    end values

    case class Param[T](code: String, value: T) derives CanEqual

    object Param:

        implicit inline def derive[T](v: => T): Param[T] =
            ${ paramImpl('v) }

        private def paramImpl[T: Type](v: Expr[T])(using Quotes): Expr[Param[T]] =
            import quotes.reflect.*
            val code = Expr(v.asTerm.pos.sourceCode.get)
            '{ Param($code, $v) }
        end paramImpl

    end Param

    private def printValue(value: Any) =
        println("──────────────────────────────".dim)
        val rendered = pprint(value).render
        val truncated =
            if rendered.length > maxValueLength then
                rendered.take(maxValueLength) + " ... (truncated)"
            else
                rendered
        println(truncated)
        println("──────────────────────────────".dim)
    end printValue
end Debug