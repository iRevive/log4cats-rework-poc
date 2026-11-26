package org.typelevel.log4cats.experimental.internal

import org.typelevel.log4cats.experimental.Fields
import org.typelevel.log4cats.experimental.Body

private[experimental] final case class RecordData(body: Body, fields: Fields, cause: Option[Throwable])
