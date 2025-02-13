/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.ast.factory.neo4j

import org.neo4j.cypher.internal.ast
import org.neo4j.cypher.internal.ast.NoOptions
import org.neo4j.cypher.internal.ast.OptionsMap
import org.neo4j.cypher.internal.ast.OptionsParam
import org.neo4j.cypher.internal.ast.Return
import org.neo4j.cypher.internal.ast.Yield
import org.neo4j.cypher.internal.expressions.ListLiteral
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.symbols.CTAny
import org.neo4j.cypher.internal.util.symbols.CTMap

class ServerManagementCommandParserTest extends AdministrationAndSchemaCommandParserTestBase {
  // SHOW

  test("SHOW SERVERS") {
    assertAst(ast.ShowServers(None)(defaultPos))
  }

  test("SHOW SERVERS YIELD *") {
    val yieldOrWhere = Left((yieldClause(returnAllItems), None))
    assertAst(ast.ShowServers(Some(yieldOrWhere))(defaultPos))
  }

  test("SHOW SERVERS YIELD address") {
    val columns = yieldClause(returnItems(variableReturnItem("address")), None)
    val yieldOrWhere = Some(Left((columns, None)))
    assertAst(ast.ShowServers(yieldOrWhere)(defaultPos))
  }

  test("SHOW SERVERS YIELD address ORDER BY name") {
    val orderByClause = Some(orderBy(sortItem(varFor("name"))))
    val columns = yieldClause(returnItems(variableReturnItem("address")), orderByClause)
    val yieldOrWhere = Some(Left((columns, None)))
    assertAst(ast.ShowServers(yieldOrWhere)(defaultPos))
  }

  test("SHOW SERVERS YIELD address ORDER BY name SKIP 1 LIMIT 2 WHERE name = 'badger' RETURN *") {
    val orderByClause = orderBy(sortItem(varFor("name")))
    val whereClause = where(equals(varFor("name"), literalString("badger")))
    val columns = yieldClause(
      returnItems(variableReturnItem("address")),
      Some(orderByClause),
      Some(skip(1)),
      Some(limit(2)),
      Some(whereClause)
    )
    val yieldOrWhere = Some(Left((columns, Some(returnAll))))
    assertAst(ast.ShowServers(yieldOrWhere)(defaultPos))
  }

  test("SHOW SERVERS YIELD * RETURN id") {
    val yieldOrWhere: Left[(Yield, Some[Return]), Nothing] =
      Left((yieldClause(returnAllItems), Some(return_(variableReturnItem("id")))))
    assertAst(ast.ShowServers(Some(yieldOrWhere))(defaultPos))
  }

  test("SHOW SERVERS WHERE name = 'badger'") {
    val yieldOrWhere = Right(where(equals(varFor("name"), literalString("badger"))))
    assertAst(ast.ShowServers(Some(yieldOrWhere))(defaultPos))
  }

  test("SHOW SERVERS RETURN *") {
    assertFailsWithMessage(
      testName,
      "Invalid input 'RETURN': expected \"WHERE\", \"YIELD\" or <EOF> (line 1, column 14 (offset: 13))"
    )
  }

  // ENABLE

  test("ENABLE SERVER 'name'") {
    assertAst(ast.EnableServer(literal("name"), NoOptions)(defaultPos))
  }

  test("ENABLE SERVER $name OPTIONS { tags: ['snake', 'flower'] }") {
    val listLiteral = ListLiteral(List(literalString("snake"), literalString("flower")))(InputPosition(36, 1, 37))
    val optionsMap = OptionsMap(Map("tags" -> listLiteral))
    assertAst(ast.EnableServer(stringParam("name"), optionsMap)(defaultPos))
  }

  test("ENABLE SERVER 'name' OPTIONS { modeConstraint: $mode }") {
    val optionsMap = OptionsMap(Map("modeConstraint" -> parameter("mode", CTAny)))
    assertAst(ast.EnableServer(literal("name"), optionsMap)(defaultPos))
  }

  test("ENABLE SERVER name") {
    assertFailsWithMessageStart(testName, """Invalid input 'name': expected "\"", "\'" or a parameter""")
  }

  test("ENABLE SERVER") {
    assertFailsWithMessageStart(testName, """Invalid input '': expected "\"", "\'" or a parameter""")
  }

  // ALTER

  test("ALTER SERVER 'name' SET OPTIONS { modeConstraint: 'PRIMARY'}") {
    val optionsMap = OptionsMap(Map("modeConstraint" -> literalString("PRIMARY")))
    assertAst(ast.AlterServer(literal("name"), optionsMap)(defaultPos))
  }

  test("ALTER SERVER $name SET OPTIONS {}") {
    val optionsMap = OptionsMap(Map.empty)
    assertAst(ast.AlterServer(stringParam("name"), optionsMap)(defaultPos))
  }

  test("ALTER SERVER 'name' SET OPTIONS $map") {
    assertAst(ast.AlterServer(literal("name"), OptionsParam(parameter("map", CTMap)))(defaultPos))
  }

  test("ALTER SERVER 'name'") {
    assertFailsWithMessageStart(testName, """Invalid input '': expected "SET"""")
  }

  test("ALTER SERVER 'name' SET OPTIONS") {
    assertFailsWithMessageStart(testName, """Invalid input '': expected "{" or a parameter""")
  }

  // RENAME

  test("RENAME SERVER 'badger' TO 'snake'") {
    assertAst(ast.RenameServer(literal("badger"), literal("snake"))(defaultPos))
  }

  test("RENAME SERVER $from TO $to") {
    assertAst(ast.RenameServer(stringParam("from"), stringParam("to"))(defaultPos))
  }

  test("RENAME SERVER `bad,ger` TO $to") {
    assertFailsWithMessageStart(testName, """Invalid input 'bad,ger': expected "\"", "\'" or a parameter""")
  }

  test("RENAME SERVER 'badger' $to") {
    assertFailsWithMessageStart(testName, "Invalid input '$': expected \"TO\"")
  }

  // DROP

  test("DROP SERVER 'name'") {
    assertAst(ast.DropServer(literal("name"))(defaultPos))
  }

  test("DROP SERVER $name") {
    assertAst(ast.DropServer(stringParam("name"))(defaultPos))
  }

  test("DROP SERVER name") {
    assertFailsWithMessage(
      testName,
      """Invalid input 'name': expected "\"", "\'" or a parameter (line 1, column 13 (offset: 12))"""
    )
  }

  test("DROP SERVER") {
    assertFailsWithMessage(
      testName,
      """Invalid input '': expected "\"", "\'" or a parameter (line 1, column 12 (offset: 11))"""
    )
  }

  // DEALLOCATE

  test("DEALLOCATE DATABASES FROM SERVER 'badger', 'snake'") {
    assertAst(ast.DeallocateServers(Seq(literal("badger"), literal("snake")))(defaultPos))
  }

  test("DEALLOCATE DATABASES FROM SERVER $name") {
    assertAst(ast.DeallocateServers(Seq(stringParam("name")))(defaultPos))
  }

  test("DEALLOCATE DATABASE FROM SERVERS $name, 'foo'") {
    assertAst(ast.DeallocateServers(Seq(stringParam("name"), literal("foo")))(defaultPos))
  }

  test("DEALLOCATE SERVERS $name, 'foo'") {
    assertFailsWithMessageStart(testName, "Invalid input 'SERVERS': expected \"DATABASE\" or \"DATABASES\"")
  }

  test("REALLOCATE DATABASE") {
    assertAst(ast.ReallocateServers()(defaultPos))
  }

  test("REALLOCATE DATABASES") {
    assertAst(ast.ReallocateServers()(defaultPos))
  }

  test("REALLOCATE SERVERS") {
    assertFailsWithMessage(
      testName,
      "Invalid input 'SERVERS': expected \"DATABASE\" or \"DATABASES\" (line 1, column 12 (offset: 11))"
    )
  }
}
