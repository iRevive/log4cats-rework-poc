package org.typelevel.log4cats.experimental.backend

import org.typelevel.log4cats.experimental.{Body, Fields, Mdc}

final case class CapturedEvent(
    meta: EventMeta,
    fields: Fields,
    body: Body,
    cause: Option[Throwable],
    mdc: Mdc = Mdc.empty
)
