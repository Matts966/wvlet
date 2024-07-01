package com.treasuredata.flow.lang.compiler.analyzer

import com.treasuredata.flow.lang.StatusCode
import com.treasuredata.flow.lang.compiler.RewriteRule.PlanRewriter
import com.treasuredata.flow.lang.compiler.{CompilationUnit, Context, Phase, RewriteRule}
import com.treasuredata.flow.lang.model.DataType.{
  FunctionType,
  NamedType,
  PrimitiveType,
  SchemaType
}
import com.treasuredata.flow.lang.model.expr.{
  Attribute,
  AttributeList,
  ContextInputRef,
  Expression,
  GroupingKey,
  Identifier,
  InterpolatedString,
  DotRef,
  ResolvedAttribute,
  This,
  UnresolvedAttribute
}
import com.treasuredata.flow.lang.model.plan.*
import com.treasuredata.flow.lang.model.{DataType, RelationType}
import wvlet.log.LogSupport

object TypeResolver extends Phase("type-resolver") with LogSupport:

  override def run(unit: CompilationUnit, context: Context): CompilationUnit =
    // resolve plans
    var resolvedPlan: LogicalPlan = TypeResolver.resolve(unit.unresolvedPlan, context)
    // resolve again to resolve unresolved relation types
    resolvedPlan = TypeResolver.resolve(resolvedPlan, context)
    unit.resolvedPlan = resolvedPlan
    unit

  def defaultRules: List[RewriteRule] =
    resolveLocalFileScan :: // resolve local file scans for DuckDb
      // resolveTableRef ::    // resolve table reference (model or schema) types
      resolveRelation :: //
      // resolveProjectedColumns ::   // TODO: Fix StackOverflowError for attr.fullName
      // resolveModelDef ::              // resolve ModelDef
      // resolveScan ::                  // resolve model/ref scan nodes
      resolveUnderscore :: // resolve underscore in relation nodes
      // resolveThis ::                  // resolve `this` in type definitions
      // resolveFunctionBodyInTypeDef :: //
      Nil

  def resolve(plan: LogicalPlan, context: Context): LogicalPlan = RewriteRule
    .rewrite(plan, defaultRules, context)

  def resolveRelation(plan: LogicalPlan, context: Context): Relation =
    val resolvedPlan = resolve(plan, context)
    resolvedPlan match
      case r: Relation =>
        r
      case _ =>
        throw StatusCode.NOT_A_RELATION.newException(s"Not a relation:\n${resolvedPlan.pp}")

  /**
    * Resolve schema of local file scans (e.g., JSON, Parquet)
    */
  object resolveLocalFileScan extends RewriteRule:
    override def apply(context: Context): PlanRewriter =
      case r: FileScan if r.path.endsWith(".json") =>
        val file             = context.getDataFile(r.path)
        val jsonRelationType = JSONAnalyzer.analyzeJSONFile(file)
        val cols = jsonRelationType
          .typeParams
          .collect { case n: NamedType =>
            n
          }
        JSONFileScan(file, jsonRelationType, cols, r.nodeLocation)
      case r: FileScan if r.path.endsWith(".parquet") =>
        val file                = context.dataFilePath(r.path)
        val parquetRelationType = ParquetAnalyzer.guessSchema(file)
        val cols = parquetRelationType
          .typeParams
          .collect { case n: NamedType =>
            n
          }
        ParquetFileScan(file, parquetRelationType, cols, r.nodeLocation)

//
//  /**
//    * Resolve TableRefs with concrete TableScans using the table schema in the catalog.
//    */
//  object resolveTableRef extends RewriteRule:
//    override def apply(context: Context): PlanRewriter =
//      case ref: TableRef =>
//        context.scope.findType(ref.name.fullName) match
//          case Some(tpe: RelationType) =>
//            context.scope.getTableDef(ref.name) match
//              case Some(tbl) =>
//                TableScan(ref.name.fullName, tpe, tpe.fields, ref.nodeLocation)
//              case None =>
//                RelScan(ref.name.fullName, tpe, tpe.fields, ref.nodeLocation)
//          case _ =>
//            trace(s"Unresolved table ref: ${ref.name.fullName}")
//            ref
//
//  object resolveScan extends RewriteRule:
//    override def apply(context: Context): PlanRewriter = {
//      case s: TableScan if !s.relationType.isResolved =>
//        context.scope.findType(s.name) match
//          case Some(r: RelationType) if r.isResolved =>
//            s.copy(schema = r)
//          case _ =>
//            trace(s"Unresolved relation type: ${s.relationType.typeName}")
//            s
//      case s: RelScan if !s.relationType.isResolved =>
//        context.scope.findType(s.name) match
//          case Some(r: RelationType) if r.isResolved =>
//            s.copy(schema = r)
//          case _ =>
//            trace(s"Unresolved relation type: ${s.relationType.typeName}")
//            s
//    }

  /**
    * Resolve expression in relation nodes
    */
  object resolveRelation extends RewriteRule:
    override def apply(context: Context): PlanRewriter = {
      case r: Relation => // Regular relation and Filter etc.
        r.transformExpressions(resolveExpression(r.inputAttributeList, context))
    }

  /**
    * Resolve select items (projected attributes) in Project nodes
    */
  object resolveProjectedColumns extends RewriteRule:
    def apply(context: Context): PlanRewriter = { case p: Project =>
      val resolvedChild = resolveRelation(p.child, context)
      val resolvedColumns: Seq[Attribute] = resolveAttributes(
        p.selectItems,
        resolvedChild.outputAttributeList,
        context
      )
      Project(resolvedChild, resolvedColumns, p.nodeLocation)
    }

//  object resolveModelDef extends RewriteRule:
//    override def apply(context: Context): PlanRewriter = { case m: ModelDef =>
//      context.scope.resolveType(m.relationType.typeName) match
//        case Some(r: RelationType) =>
//          // given model type is already resolved
//          context.scope.addType(m.name, r)
//          // context.scope.addType(r.typeName, r)
//          m.copy(relationType = r)
//        case _ if m.child.relationType.isResolved =>
//          // If the child query relation is already resolved, use this type
//          val childType = m.child.relationType
//          context.scope.addType(m.name, childType)
//          m.copy(relationType = childType)
//        case _ =>
//          m
//    }

  /**
    * Resolve underscore (_) from the parent relation node
    */
  object resolveUnderscore extends RewriteRule:
    private def hasUnderscore(r: Relation): Boolean =
      var found = false
      r.childExpressions
        .map { e =>
          e.traverseExpressions { case c: ContextInputRef =>
            found = true
          }
        }
      found

    override def apply(context: Context): PlanRewriter = {
      case u: UnaryRelation if hasUnderscore(u) =>
        given CompilationUnit = context.compilationUnit
        val contextType       = u.inputRelation.relationType
        trace(s"Resolved underscore (_) as ${contextType} in ${u.locationString}")
        val updated = u.transformChildExpressions { case expr: Expression =>
          expr.transformExpression {
            case ref: ContextInputRef if !ref.dataType.isResolved =>
              val c = ContextInputRef(dataType = contextType, ref.nodeLocation)
              c
          }
        }
        updated
    }

//  /**
//    * Resolve the type of `this` in the type definition
//    */
//  object resolveThis extends RewriteRule:
//    override def apply(context: Context): PlanRewriter = { case t: TypeDef =>
//      val parent = context.scope.findType(t.name.fullName)
//      parent match
//        case Some(r: DataType) =>
//          // TODO Handle nested definition (e.g., nested type definition)
//          t.transformUpExpressions { case th: This =>
//            val newThis = th.copy(dataType = r)
//            // trace(s"Resolved this: ${th} as ${newThis}")
//            newThis
//          }
//        case _ =>
//          t
//    }
//
//  object resolveFunctionBodyInTypeDef extends RewriteRule:
//    override def apply(context: Context): PlanRewriter = { case td: TypeDef =>
//      val attrs = td
//        .elems
//        .collect { case v: TypeValDef =>
//          val name = v.name.fullName
//          context.scope.resolveType(v.tpe.fullName) match
//            case Some(resolvedType) =>
//              ResolvedAttribute(v.name, resolvedType, None, v.nodeLocation)
//            case None =>
//              UnresolvedAttribute(v.name, v.nodeLocation)
//        }
//
//      val newElems: List[TypeElem] = td
//        .elems
//        .map {
//          case f: FunctionDef =>
//            val retType = f
//              .retType
//              .map { t =>
//                context.scope.resolveType(t.typeName) match
//                  case Some(resolvedType) =>
//                    resolvedType
//                  case None =>
//                    t
//              }
//            // Function arguments that will be used inside the expression
//            val argAttrs = f
//              .args
//              .map { arg =>
//                ResolvedAttribute(arg.name, arg.dataType, None, arg.nodeLocation)
//              }
//            // vals and function args
//            val attrList = AttributeList(attrs ++ argAttrs)
//            // warn(s"resolve function body: ${f.expr} using ${attrList}")
//            val newF = f.copy(
//              retType = retType,
//              expr = f.expr.map(x => x.transformUpExpression(resolveExpression(attrList, context)))
//            )
//            newF
//          case other =>
//            other
//        }
//      td.copy(elems = newElems)
//    }
//
//  end resolveFunctionBodyInTypeDef
//
  /**
    * Resolve the given expression type using the input attributes from child plan nodes
    *
    * @param expr
    * @param knownAttributes
    */
  private def resolveExpression(
      knownAttributes: AttributeList,
      context: Context
  ): PartialFunction[Expression, Expression] =
    case a: Attribute if !a.dataType.isResolved =>
      val name = a.fullName
      debug(s"Find ${name} in ${knownAttributes}")
      knownAttributes.attrs.find(x => x.fullName == name) match
        case Some(attr) =>
          attr
        case None =>
          a
    case ref: DotRef =>
      // Resolve types after following . (dot)
      ref.qualifier.dataType match
        case t: SchemaType =>
          trace(s"Find reference from ${t} -> ${ref.name}")
          t.columnTypes.find(_.name == ref.name) match
            case Some(col) =>
              trace(s"${t}.${col.name.fullName} is a column")
              ResolvedAttribute(ref.name, col.dataType, None, ref.nodeLocation)
            case None =>
              t.defs.find(_.name == ref.name.fullName) match
                case Some(f: FunctionType) =>
                  trace(s"Resolved ${t}.${ref.name.fullName} as a function")
                  ref.copy(dataType = f.returnType)
                case _ =>
                  warn(s"${t}.${ref.name.fullName} is not found")
                  ref
//        case p: PrimitiveType =>
//          trace(s"Find reference from ${p} -> ${ref.name}")
//          context.scope.findType(p.typeName) match
//            case Some(pt: SchemaType) =>
//              pt.defs.find(_.name == ref.fullName) match
//                case Some(m: FunctionType) =>
//                  // TODO Handling context-specific methods
//                  trace(s"Resolved ${p}.${ref.name.fullName} as a primitive function")
//                  ref.copy(dataType = m.returnType)
//                case _ =>
//                  trace(s"Failed to resolve ${p}.${ref.name.fullName}")
//                  ref
//            case _ =>
//              trace(s"Failed to resolve ${p}.${ref.name.fullName}")
//              ref
        case other =>
          // trace(s"TODO: resolve ref: ${ref.fullName} as ${other.getClass}")
          ref
    case i: InterpolatedString if i.prefix.fullName == "sql" =>
      // Ignore it because embedded SQL expressions have no static type
      i
    case i: Identifier if !i.dataType.isResolved =>
      knownAttributes.find(_.fullName == i.fullName) match
        case Some(attr) =>
          val ri = i.toResolved(attr.dataType)
          trace(s"Resolved identifier: ${ri}")
          ri
        case None =>
          trace(s"Failed to resolve identifier: ${i} from ${knownAttributes}")
          i
  end resolveExpression
//    case other: Expression if !other.dataType.isResolved =>
//      trace(s"TODO: resolve expression: ${other} using ${knownAttributes}")
//      other

  private def resolveExpression(
      expr: Expression,
      knownAttributes: AttributeList,
      context: Context
  ): Expression = resolveExpression(knownAttributes, context)
    .applyOrElse(expr, identity[Expression])

  /**
    * Resolve the given list of attribute types using known attributes from the child plan nodes as
    * hints
    *
    * @param attributes
    * @param knownAttributes
    * @param context
    * @return
    */
  private def resolveAttributes(
      attributes: Seq[Attribute],
      knownAttributes: AttributeList,
      context: Context
  ): Seq[Attribute] = attributes.map { a =>
    a.transformExpression(resolveExpression(knownAttributes, context)).asInstanceOf[Attribute]
  }

end TypeResolver
