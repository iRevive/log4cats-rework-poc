package org.typelevel.log4cats.experimental.backend

import org.typelevel.log4cats.experimental.Mdc

final case class EncodedEvent[V](
    meta: EventMeta,
    body: V,
    fields: V,
    cause: Option[Throwable],
    mdc: Mdc = Mdc.empty
)
