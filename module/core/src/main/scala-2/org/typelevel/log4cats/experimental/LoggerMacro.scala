package org.typelevel.log4cats.experimental

import scala.reflect.macros.blackbox

private[experimental] trait LoggerMacro[F[_]] {
  self: Logger[F] =>

  final def logEvent(level: Level)(event: => Event): F[Unit] = macro LoggerMacro.unnamedEvent

  final def logEvent(level: Level, eventName: Option[String])(event: => Event): F[Unit] =
    macro LoggerMacro.logEvent

  final def trace(message: => String): F[Unit] = macro LoggerMacro.message
  final def trace(message: => String, cause: Throwable): F[Unit] = macro LoggerMacro.messageWithCause
  final def debug(message: => String): F[Unit] = macro LoggerMacro.message
  final def debug(message: => String, cause: Throwable): F[Unit] = macro LoggerMacro.messageWithCause
  final def info(message: => String): F[Unit] = macro LoggerMacro.message
  final def info(message: => String, cause: Throwable): F[Unit] = macro LoggerMacro.messageWithCause
  final def warn(message: => String): F[Unit] = macro LoggerMacro.message
  final def warn(message: => String, cause: Throwable): F[Unit] = macro LoggerMacro.messageWithCause
  final def error(message: => String): F[Unit] = macro LoggerMacro.message
  final def error(message: => String, cause: Throwable): F[Unit] = macro LoggerMacro.messageWithCause
  final def fatal(message: => String): F[Unit] = macro LoggerMacro.message
  final def fatal(message: => String, cause: Throwable): F[Unit] = macro LoggerMacro.messageWithCause

  final def traceEvent(event: => Event): F[Unit] = macro LoggerMacro.event
  final def traceEvent(name: String)(event: => Event): F[Unit] = macro LoggerMacro.namedEvent
  final def debugEvent(event: => Event): F[Unit] = macro LoggerMacro.event
  final def debugEvent(name: String)(event: => Event): F[Unit] = macro LoggerMacro.namedEvent
  final def infoEvent(event: => Event): F[Unit] = macro LoggerMacro.event
  final def infoEvent(name: String)(event: => Event): F[Unit] = macro LoggerMacro.namedEvent
  final def warnEvent(event: => Event): F[Unit] = macro LoggerMacro.event
  final def warnEvent(name: String)(event: => Event): F[Unit] = macro LoggerMacro.namedEvent
  final def errorEvent(event: => Event): F[Unit] = macro LoggerMacro.event
  final def errorEvent(name: String)(event: => Event): F[Unit] = macro LoggerMacro.namedEvent
  final def fatalEvent(event: => Event): F[Unit] = macro LoggerMacro.event
  final def fatalEvent(name: String)(event: => Event): F[Unit] = macro LoggerMacro.namedEvent
}

object LoggerMacro {
  def unnamedEvent(c: blackbox.Context)(level: c.Tree)(event: c.Tree): c.Tree = {
    import c.universe._
    logEvent(c)(level, q"_root_.scala.None")(event)
  }

  def message(c: blackbox.Context)(message: c.Tree): c.Tree = {
    import c.universe._
    val level  = TermName(c.macroApplication.symbol.name.decodedName.toString.capitalize)
    val writer = TermName(c.freshName("writer"))
    q"""${c.prefix}.log(
      _root_.org.typelevel.log4cats.experimental.Level.$level,
      _root_.scala.None,
      ($writer: _root_.org.typelevel.log4cats.experimental.RecordWriter) =>
        $writer.body[_root_.java.lang.String]($message)(_root_.org.typelevel.log4cats.experimental.encoding.Encoder.stringEncoder)
    )"""
  }

  def messageWithCause(c: blackbox.Context)(message: c.Tree, cause: c.Tree): c.Tree = {
    import c.universe._
    val level         = TermName(c.macroApplication.symbol.name.decodedName.toString.capitalize)
    val receiver      = TermName(c.freshName("receiver"))
    val capturedCause = TermName(c.freshName("cause"))
    val writer        = TermName(c.freshName("writer"))
    q"""{
      val $receiver = ${c.prefix}
      val $capturedCause: _root_.java.lang.Throwable = $cause
      $receiver.log(
        _root_.org.typelevel.log4cats.experimental.Level.$level,
        _root_.scala.None,
        ($writer: _root_.org.typelevel.log4cats.experimental.RecordWriter) => {
          $writer.body[_root_.java.lang.String]($message)(_root_.org.typelevel.log4cats.experimental.encoding.Encoder.stringEncoder)
          $writer.cause($capturedCause)
        }
      )
    }"""
  }

  def event(c: blackbox.Context)(event: c.Tree): c.Tree = {
    import c.universe._
    val level = TermName(
      c.macroApplication.symbol.name.decodedName.toString.stripSuffix("Event").capitalize
    )
    val writer = TermName(c.freshName("writer"))
    q"""${c.prefix}.log(
      _root_.org.typelevel.log4cats.experimental.Level.$level,
      _root_.scala.None,
      ($writer: _root_.org.typelevel.log4cats.experimental.RecordWriter) => ($event: _root_.org.typelevel.log4cats.experimental.Event).writeTo($writer)
    )"""
  }

  def namedEvent(c: blackbox.Context)(name: c.Tree)(event: c.Tree): c.Tree = {
    import c.universe._
    val level  = TermName(c.macroApplication.symbol.name.decodedName.toString.stripSuffix("Event").capitalize)
    val writer = TermName(c.freshName("writer"))
    q"""${c.prefix}.log(
      _root_.org.typelevel.log4cats.experimental.Level.$level,
      _root_.scala.Some($name),
      ($writer: _root_.org.typelevel.log4cats.experimental.RecordWriter) =>
        ($event: _root_.org.typelevel.log4cats.experimental.Event).writeTo($writer)
    )"""
  }

  def logEvent(c: blackbox.Context)(level: c.Tree, eventName: c.Tree)(
      event: c.Tree
  ): c.Tree = {
    import c.universe._
    val writer = TermName(c.freshName("writer"))
    q"""${c.prefix}.log(
      $level,
      $eventName,
      ($writer: _root_.org.typelevel.log4cats.experimental.RecordWriter) => ($event: _root_.org.typelevel.log4cats.experimental.Event).writeTo($writer)
    )"""
  }
}
