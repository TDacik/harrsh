package at.forsyte.harrsh.parsers.slcomp

import at.forsyte.harrsh.main.HarrshLogging
import at.forsyte.harrsh.seplog._
import at.forsyte.harrsh.seplog.inductive._

import scala.collection.mutable.ListBuffer

object ScriptToSatBenchmark extends HarrshLogging {

  def DEFAULT_SELECTOR = "_def"

  def apply(s: Script, description: String): SatBenchmark = {
    if (s.asserts.length != 1) {
      throw new Exception(s"Can only build top-level symbolic heap from 1 assert, but received ${s.asserts.length}")
    }

    logger.debug(s"Will translate the following script:\n$s")
    //println(s"Will translate the following script:\n$s")

    val sorts: Set[Sort] = s.sorts.map(decl => Sort(decl.name)).toSet

    // Harrsh doesn't support named selectors, so we'll instead create a map from selector names to positions on the right-hand side of a fixed-width right-hand side
    val types: DataTypes = s.types.getOrElse(DataTypes(Nil))
    val sels: List[String] = extractSelectors(s.heap, types)
    logger.debug(s"Selectors occurring in heap: $sels")
    val selToIx: Map[String, Int] = sels.zipWithIndex.toMap
    logger.debug(s"Asscoiated with indices: $selToIx")

    val consts = s.consts.map(_.name.str)
    val constsToFvs: Map[String, Var] = Map() ++ consts.map(c => (c, FreeVar(c)))
    logger.debug(s"Mapping consts to FVs: $constsToFvs")

    val preds: Set[String] = s.funs.map(_.decl.name.str).toSet

    val env = Env(preds, types, selToIx)

    val sh = collectAtoms(s.asserts.head.term, env, constsToFvs).head.toSymbolicHeap
    val rules: Seq[(String,RuleBody)] = s.funs flatMap (fun => funDefToRules(fun, env))

    logger.debug(s"Top-level assertion: $sh")
    logger.debug(s"Predicate definitions:\n${rules.mkString("\n")}")

    val sid = SID.fromTuples("undefined", rules, description)
    val status = s.status.getOrElse(SatBenchmark.Unknown)
    SatBenchmark(sid, sh, status)
  }

  case class Env(preds: Set[String], types: DataTypes, selToIx: Map[String, Int]) {

    def mkPtrTrg(sels: List[(Var,String)]): Seq[Var] = {
      val trg: Array[Var] = Array.fill(selToIx.size)(NullConst)
      for {
        (ptr,sel) <- sels
      } trg(selToIx(sel)) = ptr
      trg.toList
    }
  }

  def funDefToRules(fun: FunDef, env: Env): Seq[(String, RuleBody)] = {
    val head = fun.decl.name.str
    val freeVars = fun.decl.args.map(_.name.str)
    val varMap = (for {
      (arg, i) <- fun.decl.args.zipWithIndex
      argStr = arg.name.str
    } yield (argStr, FreeVar(argStr))).toMap
    val atoms: Seq[Atoms] = collectAtoms(fun.term, env, varMap)
    for {
      atom <- atoms
    } yield (head, RuleBody(atom.qvars, atom.toSymbolicHeap))
  }

  case class Atoms(pure: List[PureAtom], pointsTo: List[PointsTo], predCalls: List[PredCall], qvars: List[String]) {
    def toSymbolicHeap: SymbolicHeap = {
      val atoms = AtomContainer(pure, pointsTo, predCalls)
      logger.debug(s"Creating SH from $atoms with free vars ${atoms.freeVarSeq}, bound vars: $qvars")
      SymbolicHeap(atoms, atoms.freeVarSeq)
    }

    def merge(other: Atoms) : Atoms = {
      if (qvars.nonEmpty && other.qvars.nonEmpty) throw new Exception(s"Can't merge two terms that contain bound vars: $this / $other")
      Atoms(pure ++ other.pure, pointsTo ++ other.pointsTo, predCalls ++ other.predCalls, qvars ++ other.qvars)
    }
  }
  object Atoms {
    def apply(pureAtom: PureAtom): Atoms = Atoms(List(pureAtom), Nil, Nil, Nil)
    def apply(pointsTo: PointsTo): Atoms = Atoms(Nil, List(pointsTo), Nil, Nil)
    def apply(predCall: PredCall): Atoms = Atoms(Nil, Nil, List(predCall), Nil)

    def mergeAll(atoms: Seq[Atoms]): Atoms = atoms match {
      case last +: Seq() => last
      case head +: tail => head.merge(mergeAll(tail))
    }
  }

  def collectAtoms(term: SidBuilder, env: Env, varMap: Map[String,Var]): List[Atoms] = term match {
    case Args(Symbol(fn) :: args) =>
      fn match {
        case "or" =>
          args flatMap (arg => collectAtoms(arg, env, varMap))
        case "pto" =>
          assert(args.length == 2)
          logger.debug(s"Ptr from src ${args.head} to targets ${args(1)}")
          val src = qualIdentToVar(args.head, varMap)
          val trgs = constructorToVars(args(1), env, varMap)
          val pto = PointsTo(src, trgs)
          List(Atoms(pto))
        case "and" =>
          logger.debug(s"Applying and to ${args.length} args $args")
          // TODO: Reduce code duplication w.r.t. sep
          val argAtomss = args map (arg => collectAtoms(arg, env, varMap))
          assert(argAtomss forall (_.length == 1))
          List(Atoms.mergeAll(argAtomss map (_.head)))
        case "sep" =>
          logger.debug(s"Applying sep to ${args.length} args $args")
          for (arg <- args) logger.debug (s" - $arg")
          val argAtomss = args map (arg => collectAtoms(arg, env, varMap))
          assert(argAtomss forall (_.length == 1))
          List(Atoms.mergeAll(argAtomss map (_.head)))
        case "wand" =>
          throw new Exception("No support for the magic wand")
        case "distinct" =>
          //val vars = args map (_.asInstanceOf[Symbol].str)
          val neqs = for {
            (left, i) <- args.zipWithIndex
            (right, j) <- args.zipWithIndex
            if i < j
          } yield qualIdentToVar(left, varMap) =/= qualIdentToVar(right, varMap)
          List(Atoms(neqs, Nil, Nil, Nil))
        //case "emp" =>
        //  ???
        case "=" =>
          logger.debug(s"Applying = to $args")
          assert(args.length == 2)
          val ops = args map (arg => qualIdentToVar(arg, varMap))
          List(Atoms(ops(0) =:= ops(1)))
        case pred if env.preds.contains(pred) =>
          val callArgs = args map (arg => qualIdentToVar(arg, varMap))
          List(Atoms(PredCall(pred, callArgs)))
        case other =>
          throw new Exception(s"Can't convert $other to symbolic heap")
      }
    case IndexedIdentifier(Symbol("emp"),_) =>
      List(Atoms(Nil, Nil, Nil, Nil))
    case Exists(vars, term) =>
      val qvars: List[String] = vars map (_.name.str)
      val qvarMap = qvars.zipWithIndex.map {
        case (str,ix) => (str,BoundVar(ix+1))
      }
      val extendedMap = varMap ++ qvarMap
      val termAtoms = collectAtoms(term, env, extendedMap)
      assert(termAtoms.length == 1)
      List(termAtoms.head.copy(qvars = qvars))
    case other =>
      throw new Exception(s"Can't convert $other to symbolic heap")
  }

  def constructorToVars(sid : SidBuilder, env: Env, varMap: Map[String,Var]): Seq[Var] = sid match {
    case Args((s@Symbol(hd)) :: tl) =>
      if (tl.isEmpty) {
        env.mkPtrTrg(List((qualIdentToVar(s, varMap), DEFAULT_SELECTOR)))
      } else {
        val args = tl map (arg => qualIdentToVar(arg, varMap))
        val c = env.types.getConstructor(hd)
        val sels = c.sels.map(_.sel.str)
        env.mkPtrTrg(args zip sels)
      }
    case other =>
      throw new Exception(s"Can't convert $other to constructor application")
  }

  def qualIdentToVar(sid : SidBuilder, varMap: Map[String,Var]): Var = sid match {
    case Symbol(str) => varMap(str)
    case QualifiedIdentifier(Symbol(str), _) =>
      // We don't care about the type info
      if (str == "nil") NullConst else throw new Exception(s"Unexpected qualified identifier $str")
    case other =>
      throw new Exception(s"Can't interpret $sid as qualified identifier")
  }

  def extractSelectors(heapDecl: HeapDecl, types: DataTypes): List[String] = {
    val res = ListBuffer.empty[String]

    for {
      (src, trg) <- heapDecl.mapping
    } {
      val maybeDt = types.get(trg.symbol.str)
      maybeDt match {
        case None =>
          // The target is a built-in type => anonymous selector field
          res += DEFAULT_SELECTOR
        case Some(dt) =>
          for {
            c <- dt.constructors
            selDecl <- c.sels
          } {
            res += selDecl.sel.str
          }
      }
    }

    res.toList.distinct
  }

}
