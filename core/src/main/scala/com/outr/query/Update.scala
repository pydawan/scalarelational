package com.outr.query

/**
 * @author Matt Hicks <matt@outr.com>
 */
case class Update(values: List[ColumnValue[_]],
                  table: Table,
                  whereCondition: Condition = null) extends WhereSupport[Update] {
  def where(condition: Condition) = copy(whereCondition = condition)
}