package org.typelevel.log4cats.experimental

import cats.Id

/** Synchronous logging inside a ScopedLogging callback. Do not retain it beyond that callback. */
trait ScopedLogger extends Logger[Id]
