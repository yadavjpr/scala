/* NSC -- new Scala compiler
 * Copyright 2005-2006 LAMP/EPFL
 * @author
 */
// $Id$

package scala.tools.nsc.transform

import scala.collection.mutable.ListBuffer
import symtab.Flags._
import util.TreeSet

/** The abstract class <code>Constructors</code> ...
 *
 *  @author  Martin Odersky
 *  @version 1.0
 */
abstract class Constructors extends Transform {
  import global._
  import definitions._
  import posAssigner.atPos

  /** the following two members override abstract members in Transform */
  val phaseName: String = "constructors"

  protected def newTransformer(unit: CompilationUnit): Transformer =
    new ConstructorTransformer

  class ConstructorTransformer extends Transformer {

    def transformClassTemplate(impl: Template): Template = {
      val clazz = impl.symbol.owner
      val stats = impl.body
      val localTyper = typer.atOwner(impl, clazz)
      var constr: DefDef = null
      var constrParams: List[Symbol] = null
      var constrBody: Block = null
      // decompose primary constructor into the three entities above.
      for (val stat <- stats) {
        stat match {
          case ddef @ DefDef(_, _, _, List(vparams), _, rhs @ Block(_, Literal(_))) =>
            if (ddef.symbol.isPrimaryConstructor) {
              constr = ddef
              constrParams = vparams map (.symbol)
              constrBody = rhs
            }
          case _ =>
        }
      }
      assert((constr ne null) && (constrBody ne null), impl)

      val paramAccessors = clazz.constrParamAccessors

      def parameter(acc: Symbol): Symbol =
        parameterNamed(nme.getterName(acc.originalName))

      def parameterNamed(name: Name): Symbol = {
        val ps = constrParams.filter(param => param.name == name || param.originalName == name)
        if (ps.isEmpty) assert(false, "" + name + " not in " + constrParams)
        ps.head
      }

      var thisRefSeen: boolean = false

      val intoConstructorTransformer = new Transformer {
        override def transform(tree: Tree): Tree = tree match {
          case Apply(Select(This(_), _), List()) =>
            if ((tree.symbol hasFlag PARAMACCESSOR) && tree.symbol.owner == clazz) {
              thisRefSeen = false
              gen.mkAttributedIdent(parameter(tree.symbol.accessed)) setPos tree.pos
            }
            else if (tree.symbol.outerSource == clazz && !clazz.isImplClass) {
              thisRefSeen = false
              gen.mkAttributedIdent(parameterNamed(nme.OUTER))
            }
            else
              super.transform(tree)
          case Select(This(_), _)
          if ((tree.symbol hasFlag PARAMACCESSOR) && tree.symbol.owner == clazz) =>
            thisRefSeen = false
            gen.mkAttributedIdent(parameter(tree.symbol)) setPos tree.pos
          case Apply(fun, args) =>
            var tmpRefSeen = thisRefSeen
            val fun1 = transform(fun)
            thisRefSeen = tmpRefSeen || thisRefSeen
            val args1 = List.mapConserve(args){ tree =>
              tmpRefSeen = thisRefSeen
              val res = transform(tree)
              thisRefSeen = tmpRefSeen || thisRefSeen
              res
            }
            copy.Apply(tree, fun1, args1)
          case Block(_, _) =>
            val res = super.transform(tree)
            thisRefSeen = true
            res
          case This(_) | Super(_, _) | Select(_, _) =>
            thisRefSeen = true
            super.transform(tree)
          case _ =>
            super.transform(tree)
        }
      }

      def intoConstructor(oldowner: Symbol, tree: Tree) =
        intoConstructorTransformer.transform(
          new ChangeOwnerTraverser(oldowner, constr.symbol)(tree))

      def canBeMoved(tree: Tree) = tree match {
        case ValDef(_, _, _, _) => !thisRefSeen
        case _ => false
      }

      def mkAssign(to: Symbol, from: Tree): Tree =
        atPos(to.pos) {
          localTyper.typed {
            Assign(Select(This(clazz), to), from)
          }
        }

      def copyParam(to: Symbol, from: Symbol): Tree = {
        var result = mkAssign(to, Ident(from))
        if (from.name == nme.OUTER)
          result =
            atPos(to.pos) {
              localTyper.typed {
                If(Apply(Select(Ident(from), nme.eq), List(Literal(Constant(null)))),
                   Throw(New(TypeTree(NullPointerExceptionClass.tpe), List(List()))),
                   result);
              }
            }
        result
      }

      val defBuf = new ListBuffer[Tree]
      val constrStatBuf = new ListBuffer[Tree]
      val constrPrefixBuf = new ListBuffer[Tree]
      constrBody.stats foreach (constrStatBuf +=)

      for (val stat <- stats) stat match {
        case DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
          stat.symbol.tpe match {
            case MethodType(List(), tp @ ConstantType(c)) =>
              defBuf += copy.DefDef(
                stat, mods, name, tparams, vparamss, tpt,
                Literal(c) setPos rhs.pos setType tp)
            case _ =>
              if (!stat.symbol.isPrimaryConstructor) defBuf += stat
          }
        case ValDef(mods, name, tpt, rhs) =>
          if (stat.symbol.tpe.isInstanceOf[ConstantType])
            assert(stat.symbol.getter(stat.symbol.owner) != NoSymbol, stat)
          else {
            if (rhs != EmptyTree) {
              val rhs1 = intoConstructor(stat.symbol, rhs)
              (if (canBeMoved(stat)) constrPrefixBuf else constrStatBuf) += mkAssign(
                stat.symbol, rhs1)
            }
            defBuf += copy.ValDef(stat, mods, name, tpt, EmptyTree)
          }
        case ClassDef(_, _, _, _, _) =>
          defBuf += (new ConstructorTransformer).transform(stat)
        case _ =>
          constrStatBuf += intoConstructor(impl.symbol, stat)
      }

      val accessed = new TreeSet[Symbol]((x, y) => x isLess y)

      def isAccessed(sym: Symbol) =
        sym.owner != clazz ||
        !(sym hasFlag PARAMACCESSOR) ||
        !(sym hasFlag LOCAL) ||
        (accessed contains sym)

      val accessTraverser = new Traverser {
        override def traverse(tree: Tree) = {
          tree match {
            case Select(_, _) =>
              if (!isAccessed(tree.symbol)) accessed addEntry tree.symbol
            case _ =>
          }
          super.traverse(tree)
        }
      }

      for (val stat <- defBuf.elements) accessTraverser.traverse(stat)

      val paramInits = for (val acc <- paramAccessors; isAccessed(acc))
                       yield copyParam(acc, parameter(acc))

      defBuf += copy.DefDef(
        constr, constr.mods, constr.name, constr.tparams, constr.vparamss, constr.tpt,
        copy.Block(
          constrBody,
          paramInits ::: constrPrefixBuf.toList ::: constrStatBuf.toList,
          constrBody.expr));

      copy.Template(impl, impl.parents,
        defBuf.toList filter (stat => isAccessed(stat.symbol)))
    }

    override def transform(tree: Tree): Tree = {
      tree match {
        case ClassDef(mods, name, tparams, tpt, impl)
        if !tree.symbol.hasFlag(INTERFACE) =>
          copy.ClassDef(tree, mods, name, tparams, tpt, transformClassTemplate(impl))
        case _ =>
          super.transform(tree)
      }
    }

  } // ConstructorTransformer

}
