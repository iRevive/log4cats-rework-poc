package org.typelevel.log4cats.experimental.backend

import org.typelevel.log4cats.experimental.Fields
import org.typelevel.vault.Vault

final case class CapturedEvent(
    meta: EventMeta,
    fields: Fields,
    body: Body,
    cause: Option[Throwable],
    attachments: Vault
)
