package org.typelevel.log4cats.experimental.pipeline.internal

import org.typelevel.log4cats.experimental.Fields
import org.typelevel.log4cats.experimental.backend.Body

private[pipeline] final case class RecordData(body: Body, fields: Fields, cause: Option[Throwable])
