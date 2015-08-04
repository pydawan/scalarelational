package org.scalarelational.instruction

import org.scalarelational.table.Table
import org.scalarelational.column.ColumnValue

/**
 * @author Matt Hicks <matt@outr.com>
 */
case class InsertSingle[ResultType](table: Table,
                                    values: Seq[ColumnValue[_]],
                                    mapResult: Int => ResultType
                                   )
  extends Insert[ResultType] with Instruction[ResultType] {
  lazy val rows = Seq(values)

  def result: ResultType = mapResult(table.datastore.exec(this))

  def and(nextRow: ColumnValue[_]*): InsertMultiple =
    InsertMultiple(table, Seq(values, nextRow))

  override def add(value: ColumnValue[_]): InsertSingle[ResultType] = {
    val filtered = values.filterNot(_.column == value.column)
    copy(values = value :: filtered.toList)
  }
}
