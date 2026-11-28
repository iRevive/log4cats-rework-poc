package org.typelevel.log4cats.experimental

object syntax {
  implicit final class LoggerOps[F[_]](private val underlying: Logger[F]) extends AnyVal {

    /**
      * Prepend static structured defaults inside the lazy record callback. Later enrichment
      * wins collisions; per-call values win last. Rejected callbacks do not write
      * or encode these defaults.
      */
    def withFields(fields: Fields): Logger[F] =
      if (fields.isEmpty) underlying
      else
        new Logger[F] {
          def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): F[Unit] =
            underlying.log(
              level,
              eventName,
              writer => {
                writer.fields(fields)
                write(writer)
              }
            )
        }

    /**
      * Prepend static diagnostic defaults inside the lazy record callback. Later enrichment
      * wins collisions; per-call values win last. These defaults are unavailable
      * to early native acceptance checks; use a ContextSource when acceptance depends on MDC.
      */
    def withMdc(mdc: Mdc): Logger[F] =
      if (mdc.isEmpty) underlying
      else
        new Logger[F] {
          def log(level: Level, eventName: Option[String], write: RecordWriter => Unit): F[Unit] =
            underlying.log(
              level,
              eventName,
              writer => {
                writer.mdc(mdc)
                write(writer)
              }
            )
        }
  }
}
