package org.scalarelational.h2.trigger

import org.powerscala.enum.{EnumEntry, Enumerated}


sealed abstract class TriggerState extends EnumEntry

object TriggerState extends Enumerated[TriggerState] {
  case object Before extends TriggerState
  case object After extends TriggerState

  val values = findValues.toVector
}