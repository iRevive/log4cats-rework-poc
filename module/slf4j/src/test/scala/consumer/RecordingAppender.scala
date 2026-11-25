package consumer

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.ConcurrentLinkedQueue

private final class RecordingAppender extends AppenderBase[ILoggingEvent] {
  val events = new ConcurrentLinkedQueue[ILoggingEvent]()

  protected def append(event: ILoggingEvent): Unit = {
    event.prepareForDeferredProcessing()
    events.add(event)
    ()
  }
}
