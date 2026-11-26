package org.typelevel.log4cats.experimental.backend

import org.typelevel.vault.Vault

final case class EncodedEvent[V](
    meta: EventMeta,
    body: V,
    fields: V,
    cause: Option[Throwable],
    attachments: Vault
)
