package org.typelevel.log4cats.experimental

final case class Scope(name: String) {
  require(name != null && name.nonEmpty, "scope name must be nonempty")
}
