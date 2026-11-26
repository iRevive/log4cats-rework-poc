package org.typelevel.log4cats.experimental.context

import org.typelevel.log4cats.experimental.Fields
import org.typelevel.vault.Vault

final case class LogContext(fields: Fields = Fields.empty, attachments: Vault = Vault.empty)
