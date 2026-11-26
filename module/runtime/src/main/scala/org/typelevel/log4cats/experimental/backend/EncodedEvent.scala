package org.typelevel.log4cats.experimental.backend

final case class EncodedEvent[V](
    meta: EventMeta,
    body: V,
    fields: V,
    cause: Option[Throwable]
)
