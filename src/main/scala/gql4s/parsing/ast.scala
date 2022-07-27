// Copyright (c) 2021 by Oliver Winks
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package gql4s
package parsing

import cats.data.NonEmptyList
import scala.annotation.tailrec

import OperationType.*
import Type.*
import Value.*
import scala.reflect.TypeTest

sealed trait HasType:
  def `type`: Type

// given HasType[VariableDefinition] with
//   extension (t: VariableDefinition) def getType = t.`type`

// given HasType[FieldDefinition] with
//   extension (t: FieldDefinition) def getType = t.`type`

// given HasType[InputValueDefinition] with
//   extension (t: InputValueDefinition) def getType = t.`type`

sealed trait HasName:
  def name: Name

// given HasName[Type] with
//   extension (t: Type) def getName = t.name

// given HasName[VariableDefinition] with
//   extension (t: VariableDefinition) def getName = t.name

// given HasName[FragmentDefinition] with
//   extension (t: FragmentDefinition) def getName = t.name

// given HasName[Field] with
//   extension (t: Field) def getName = t.name

// given HasName[FragmentSpread] with
//   extension (t: FragmentSpread) def getName = t.name

// given HasName[TypeDefinition] with
//   extension (t: TypeDefinition) def getName = t.name

// given HasName[TypeExtension] with
//   extension (t: TypeExtension) def getName = t.name

// given HasName[InputValueDefinition] with
//   extension (t: InputValueDefinition) def getName = t.name

// given HasName[FieldDefinition] with
//   extension (t: FieldDefinition) def getName = t.name

// given HasName[EnumValueDefinition] with
//   extension (t: EnumValueDefinition) def getName = t.name

sealed trait HasFields:
  def fields: List[FieldDefinition]

// given HasFields[ObjectLikeTypeDefinition] with
//   extension (t: ObjectLikeTypeDefinition) def getFields = t.fields

sealed trait HasArgs:
  def arguments: List[InputValueDefinition]

// given HasArgs[FieldDefinition] with
//   extension (t: FieldDefinition) def getArguments = t.arguments

// given HasArgs[InputObjectTypeDefinition] with
//   extension (t: InputObjectTypeDefinition) def getArguments = t.fields

// given HasArgs[DirectiveDefinition] with
//   extension (t: DirectiveDefinition) def getArguments = t.arguments

sealed trait HasInterfaces:
  def interfaces: List[NamedType]

// given HasInterfaces[ObjectLikeTypeDefinition] with
//   extension (t: ObjectLikeTypeDefinition) def getInterfaces = t.interfaces

sealed trait HasSelectionSet:
  def selectionSet: SelectionSet

///////////////////////////////////////////////////////////////////////////////////////////////////
// Definitions & Documents
case class ExecutableDocument(definitions: NonEmptyList[ExecutableDefinition]):
  def findExecDef[T <: ExecutableDefinition](using TypeTest[Any, T]) =
    definitions.collect { case t: T => t }

  def findFragDef(fragName: Name): Option[FragmentDefinition] =
    definitions.collect { case f: FragmentDefinition => f }.find(_.name == fragName)

  def getDef[T](using TypeTest[Any, T]): List[T] = definitions.collect { case t: T => t }

  /** Finds unique uses of fragment spreads.
    *
    * @return
    *   A list of fragment spreads which are used in the given document. No duplicates will exist in
    *   the list.
    */
  def findFragSpreads(): List[FragmentSpread] =
    @tailrec
    def recurse(accSelectionSet: List[Selection], acc: List[FragmentSpread]): List[FragmentSpread] =
      accSelectionSet match
        case Nil => acc
        case head :: tail =>
          head match
            case Field(_, _, _, _, selectionSet)    => recurse(selectionSet ::: tail, acc)
            case InlineFragment(_, _, selectionSet) => recurse(selectionSet.toList ::: tail, acc)
            case spread: FragmentSpread =>
              val acc2 = if acc.contains(spread) then acc else spread :: acc
              recurse(tail, acc2)
    end recurse

    definitions
      .collect { case o: OperationDefinition => o }
      .map(_.selectionSet.toList)
      .flatMap(recurse(_, Nil))
  end findFragSpreads
end ExecutableDocument

case class TypeSystemDocument(definitions: NonEmptyList[TypeSystemDefinition]):
  val scalarDefs = List(
    ScalarTypeDefinition(Name("Int"), Nil),
    ScalarTypeDefinition(Name("Float"), Nil),
    ScalarTypeDefinition(Name("String"), Nil),
    ScalarTypeDefinition(Name("Boolean"), Nil),
    ScalarTypeDefinition(Name("ID"), Nil)
  )

  def isLeafType(name: Name): Boolean = name.text match
    case "Int" | "Float" | "String" | "Boolean" | "ID" => true
    case _                                             => false

  def isInputType(`type`: Type): Boolean = `type` match
    case NonNullType(tpe) => isInputType(tpe)
    case ListType(tpe)    => isInputType(tpe)
    case NamedType(name) =>
      findTypeDef[TypeDefinition](name) match
        case Some(_: ScalarTypeDefinition)      => true
        case Some(_: EnumTypeDefinition)        => true
        case Some(_: InputObjectTypeDefinition) => true
        case _                                  => isLeafType(name)
  end isInputType

  def isOutputType(`type`: Type): Boolean = `type` match
    case NonNullType(tpe) => isOutputType(tpe)
    case ListType(tpe)    => isOutputType(tpe)
    case NamedType(name) =>
      findTypeDef[TypeDefinition](name) match
        case Some(_: ScalarTypeDefinition)    => true
        case Some(_: ObjectTypeDefinition)    => true
        case Some(_: InterfaceTypeDefinition) => true
        case Some(_: UnionTypeDefinition)     => true
        case Some(_: EnumTypeDefinition)      => true
        case _                                => isLeafType(name)
  end isOutputType

  def isObjectType(name: Name): Boolean = findTypeDef[ObjectTypeDefinition](name).isDefined

  def findTypeDef[T <: TypeDefinition](name: Name)(using TypeTest[Any, T]): Option[T] =
    (definitions ++ scalarDefs).collect { case t: T => t }.find(_.name == name)

  def getTypeDef[T](using TypeTest[Any, T]): List[T] =
    (definitions ++ scalarDefs).collect { case t: T => t }

  /** Checks whether the given field exists within the given type.
    *
    * @param fieldName
    *   The field we're looking for.
    * @param namedType
    *   The name of the type to search in.
    * @param schema
    *   The graphql schema.
    * @return
    *   Some MissingField error if the field cannot be found, None if it can.
    */
  def findFieldDef(fieldName: Name, namedType: NamedType): Option[FieldDefinition] =
    @tailrec
    def recurse(namedTypes: List[NamedType]): Option[FieldDefinition] =
      namedTypes match
        case Nil => None
        case namedType :: tail =>
          val typeDef = findTypeDef[TypeDefinition](namedType.name)

          typeDef match
            case Some(ObjectTypeDefinition(_, interfaces, _, fields)) =>
              val fieldDef = fields.find(_.name == fieldName)
              if fieldDef.isDefined then fieldDef
              else recurse(interfaces ::: tail)

            case Some(InterfaceTypeDefinition(_, interfaces, _, fields)) =>
              val fieldDef = fields.find(_.name == fieldName)
              if fieldDef.isDefined then fieldDef
              else recurse(interfaces ::: tail)

            case Some(UnionTypeDefinition(_, _, members)) =>
              recurse(members ::: tail)

            // The type that we're checking exists but isn't a type with fields
            // therefore the field can't exist so we just return false
            case _ => None
    end recurse

    recurse(List(namedType))
  end findFieldDef

  def findOpTypeDef(opType: OperationType): Option[ObjectTypeDefinition] =
    val schemaDef = definitions.collect { case s: SchemaDefinition => s }.headOption
    schemaDef match
      case Some(SchemaDefinition(_, roots)) =>
        roots
          .find(_.operationType == opType)
          .map(_.namedType.name)
          .flatMap(findTypeDef[ObjectTypeDefinition])
      case None =>
        opType match
          case Query        => findTypeDef[ObjectTypeDefinition](Name("Query"))
          case Mutation     => findTypeDef[ObjectTypeDefinition](Name("Mutation"))
          case Subscription => findTypeDef[ObjectTypeDefinition](Name("Subscription"))
  end findOpTypeDef
end TypeSystemDocument

sealed trait Definition
sealed trait ExecutableDefinition extends Definition

///////////////////////////////////////////////////////////////////////////////////////////////////
// Queries
case class Name(text: String)

// Types
enum Type(val name: Name) extends HasName:
  case NamedType(override val name: Name) extends Type(name)
  case NonNullType(`type`: Type)          extends Type(`type`.name)
  case ListType(`type`: Type)             extends Type(`type`.name)

// Values
case class VariableDefinition(name: Name, `type`: Type, defaultValue: Option[Value])
    extends HasType,
      HasName

case class ObjectField(name: Name, value: Value) extends HasName
enum Value:
  case Variable(name: Name)
  case IntValue(value: Int)
  case FloatValue(value: Float)
  case StringValue(value: String)
  case BooleanValue(value: Boolean)
  case NullValue
  case ListValue(values: List[Value])
  case EnumValue(name: Name)
  case ObjectValue(fields: List[ObjectField])

// Arguments
case class Argument(name: Name, value: Value) extends HasName

// Directives
case class Directive(name: Name, arguments: List[Argument])

// Fragments
case class FragmentDefinition(
    override val name: Name,
    on: NamedType,
    directives: List[Directive],
    selectionSet: NonEmptyList[Selection]
) extends ExecutableDefinition,
      HasName

type SelectionSet = NonEmptyList[Selection]
sealed trait Selection // TODO: this should be an enum! Once HasName is a typeclass see if you can add an instance of HasName[FragmentSpread]

case class Field(
    alias: Option[Name],
    name: Name,
    arguments: List[Argument],
    directives: List[Directive],
    selectionSet: List[Selection]
) extends Selection,
      HasName

case class InlineFragment(
    onType: Option[Type.NamedType],
    directives: List[Directive],
    selectionSet: NonEmptyList[Selection]
) extends Selection

case class FragmentSpread(name: Name, directives: List[Directive]) extends Selection, HasName

// Operations
enum OperationType:
  case Query, Mutation, Subscription

case class OperationDefinition(
    name: Name,
    operationType: OperationType,
    variableDefinitions: List[VariableDefinition],
    directives: List[Directive],
    selectionSet: NonEmptyList[Selection]
) extends ExecutableDefinition,
      HasSelectionSet,
      HasName

///////////////////////////////////////////////////////////////////////////////////////////////////
// Schema
sealed trait TypeSystemDefinition            extends Definition
sealed trait TypeSystemExtension             extends Definition
sealed trait TypeSystemDefinitionOrExtension extends Definition

sealed trait TypeDefinition extends TypeSystemDefinition, HasName:
  def name: Name

sealed trait TypeExtension extends TypeSystemExtension, HasName:
  def name: Name

case class RootOperationTypeDefinition(operationType: OperationType, namedType: NamedType)

case class SchemaDefinition(
    directives: List[Directive],
    rootOperationTypeDefinition: NonEmptyList[RootOperationTypeDefinition]
) extends TypeSystemDefinition

case class SchemaExtension(directives: List[Directive], root: List[RootOperationTypeDefinition])
    extends TypeSystemExtension

// Scalar Type
case class ScalarTypeDefinition(name: Name, directives: List[Directive]) extends TypeDefinition
case class ScalarTypeExtension(name: Name, directives: NonEmptyList[Directive])
    extends TypeExtension

// Object Type
case class InputValueDefinition(
    name: Name,
    `type`: Type,
    defaultValue: Option[Value],
    directives: List[Directive]
) extends HasType,
      HasName

case class FieldDefinition(
    name: Name,
    arguments: List[InputValueDefinition],
    `type`: Type,
    directives: List[Directive]
) extends HasType,
      HasName,
      HasArgs

trait ObjectLikeTypeDefinition extends TypeDefinition, HasName, HasFields, HasInterfaces:
  def name: Name
  def fields: List[FieldDefinition]
  def interfaces: List[NamedType]

case class ObjectTypeDefinition(
    name: Name,
    interfaces: List[NamedType],
    directives: List[Directive],
    fields: List[FieldDefinition]
) extends ObjectLikeTypeDefinition

case class ObjectTypeExtension(
    name: Name,
    interfaces: List[NamedType],
    directives: List[Directive],
    fields: List[FieldDefinition]
) extends TypeExtension

// Interfaces
case class InterfaceTypeDefinition(
    name: Name,
    interfaces: List[NamedType],
    directives: List[Directive],
    fields: List[FieldDefinition]
) extends ObjectLikeTypeDefinition

case class InterfaceTypeExtension(
    name: Name,
    interfaces: List[NamedType],
    directives: List[Directive],
    fields: List[FieldDefinition]
) extends TypeExtension

// Unions
case class UnionTypeDefinition(
    name: Name,
    directives: List[Directive],
    unionMemberTypes: List[Type.NamedType]
) extends TypeDefinition

case class UnionTypeExtension(
    name: Name,
    directives: List[Directive],
    unionMembers: List[NamedType]
) extends TypeExtension

// Enum Type Definition
case class EnumValueDefinition(value: EnumValue, directives: List[Directive]) extends HasName:
  export value.*

case class EnumTypeDefinition(
    name: Name,
    directives: List[Directive],
    values: List[EnumValueDefinition]
) extends TypeDefinition

case class EnumTypeExtension(
    name: Name,
    directives: List[Directive],
    values: List[EnumValueDefinition]
) extends TypeExtension

// Input Objects
case class InputObjectTypeDefinition(
    name: Name,
    directives: List[Directive],
    fields: List[InputValueDefinition]
) extends TypeDefinition,
      HasArgs:
  def arguments = fields // Although these appear as fields they are also (kind of) arguments

case class InputObjectTypeExtension(
    name: Name,
    directives: List[Directive],
    fieldsDef: List[InputValueDefinition]
) extends TypeExtension

// Directives
sealed trait DirectiveLocation

enum TypeSystemDirectiveLocation extends DirectiveLocation:
  case SCHEMA, SCALAR, OBJECT, FIELD_DEFINITION, ARGUMENT_DEFINITION, INTERFACE, UNION, ENUM,
    ENUM_VALUE, INPUT_OBJECT, INPUT_FIELD_DEFINITION

enum ExecutableDirectiveLocation extends DirectiveLocation:
  case QUERY, MUTATION, SUBSCRIPTION, FIELD, FRAGMENT_DEFINITION, FRAGMENT_SPREAD, INLINE_FRAGMENT,
    VARIABLE_DEFINITION

case class DirectiveDefinition(
    name: Name,
    arguments: List[InputValueDefinition],
    repeatable: Boolean,
    directiveLocs: NonEmptyList[DirectiveLocation]
) extends TypeSystemDefinition,
      HasArgs