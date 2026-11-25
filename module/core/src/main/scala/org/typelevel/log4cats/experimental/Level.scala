package org.typelevel.log4cats.experimental

sealed abstract class Level(val rank: Int)

object Level {
  case object Trace extends Level(0)
  case object Debug extends Level(10)
  case object Info  extends Level(20)
  case object Warn  extends Level(30)
  case object Error extends Level(40)
  case object Fatal extends Level(50)
}
