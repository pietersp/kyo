package kyo.concurrent.scheduler

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

private object ThreadFactory {

  def apply(name: String): ThreadFactory =
    apply(name, new Thread(_))

  def apply(name: String, create: Runnable => Thread): ThreadFactory =
    new ThreadFactory {
      val nextId = new AtomicInteger
      def newThread(r: Runnable): Thread =
        val t = create(r)
        t.setName(name + "-" + nextId.incrementAndGet)
        t.setDaemon(true)
        t
    }
}
