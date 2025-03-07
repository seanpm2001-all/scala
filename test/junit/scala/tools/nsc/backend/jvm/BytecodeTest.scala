package scala.tools.nsc.backend.jvm

import org.junit.Assert._
import org.junit.Test

import scala.annotation.unused
import scala.jdk.CollectionConverters._
import scala.tools.asm.Opcodes._
import scala.tools.testkit.ASMConverters._
import scala.tools.testkit.BytecodeTesting
import scala.tools.testkit.BytecodeTesting._
import scala.tools.asm.Opcodes
import scala.tools.asm.tree.MethodNode

class BytecodeTest extends BytecodeTesting {
  import compiler._

  @Test
  def t10812(): Unit = {
    def code(prefix: String) =
      s"""$prefix A { def f: Object = null }
        |object B extends A { override def f: String = "b" }
      """.stripMargin
    for (base <- List("trait", "class")) {
      val List(a, bMirror, bModule) = compileClasses(code(base))
      assertEquals(bMirror.name, "B")
      assertEquals(bMirror.methods.asScala.filter(_.name == "f").map(m => m.name + m.desc).toList, List("f()Ljava/lang/String;"))
    }
  }

  @Test
  def t6288bJumpPosition(): Unit = {
    val code =
      """object Case3 {                                 // 01
        | def unapply(z: Any): Option[Int] = Some(-1)   // 02
        | def main(args: Array[String]): Unit = {       // 03
        |    ("": Any) match {                          // 04
        |      case x : String =>                       // 05
        |        println("case 0")                      // 06 println and jump at 6
        |      case _ =>                                // 07
        |        println("default")                     // 08 println and jump at 8
        |    }                                          // 09
        |    println("done")                            // 10
        |  }
        |}
      """.stripMargin
    val List(mirror, module) = compileClasses(code)

    val unapplyLineNumbers = getInstructions(module, "unapply").filter(_.isInstanceOf[LineNumber])
    assert(unapplyLineNumbers == List(LineNumber(2, Label(0))), unapplyLineNumbers)

    val expected = List(
      LineNumber(4, Label(0)),
      LineNumber(5, Label(5)),
      Jump(IFEQ, Label(20)),

      LineNumber(6, Label(11)),
      Invoke(INVOKEVIRTUAL, "scala/Predef$", "println", "(Ljava/lang/Object;)V", false),
      Jump(GOTO, Label(33)),

      LineNumber(5, Label(20)),
      Jump(GOTO, Label(24)),

      LineNumber(8, Label(24)),
      Invoke(INVOKEVIRTUAL, "scala/Predef$", "println", "(Ljava/lang/Object;)V", false),
      Jump(GOTO, Label(33)),

      LineNumber(10, Label(33)),
      Invoke(INVOKEVIRTUAL, "scala/Predef$", "println", "(Ljava/lang/Object;)V", false)
    )

    val mainIns = getInstructions(module, "main") filter {
      case _: LineNumber | _: Invoke | _: Jump => true
      case _ => false
    }
    assertSameCode(mainIns, expected)
  }

  @Test
  def bytecodeForBranches(): Unit = {
    val code =
      """class C {
        |  def t1(b: Boolean) = if (b) 1 else 2
        |  def t2(x: Int) = if (x == 393) 1 else 2
        |  def t3(a: Array[String], b: AnyRef) = a != b && b == a
        |  def t4(a: AnyRef) = a == null || null != a
        |  def t5(a: AnyRef) = (a eq null) || (null ne a)
        |  def t6(a: Int, b: Boolean) = if ((a == 10) && b || a != 1) 1 else 2
        |  def t7(a: AnyRef, b: AnyRef) = a == b
        |  def t8(a: AnyRef) = scala.collection.immutable.Nil == a || "" != a
        |}
      """.stripMargin

    val c = compileClass(code)

    // t1: no unnecessary GOTOs
    assertSameCode(getMethod(c, "t1"), List(
      VarOp(ILOAD, 1), Jump(IFEQ, Label(6)),
      Op(ICONST_1), Jump(GOTO, Label(9)),
      Label(6), Op(ICONST_2),
      Label(9), Op(IRETURN)))

    // t2: no unnecessary GOTOs
    assertSameCode(getMethod(c, "t2"), List(
      VarOp(ILOAD, 1), IntOp(SIPUSH, 393), Jump(IF_ICMPNE, Label(7)),
      Op(ICONST_1), Jump(GOTO, Label(10)),
      Label(7), Op(ICONST_2),
      Label(10), Op(IRETURN)))

    // t3: Array == is translated to reference equality, AnyRef == to null checks and equals
    assertSameCode(getMethod(c, "t3"), List(
      // Array ==
      VarOp(ALOAD, 1), VarOp(ALOAD, 2), Jump(IF_ACMPEQ, Label(23)),
      // AnyRef ==
      VarOp(ALOAD, 2), VarOp(ALOAD, 1), VarOp(ASTORE, 3), Op(DUP), Jump(IFNONNULL, Label(14)),
      Op(POP), VarOp(ALOAD, 3), Jump(IFNULL, Label(19)), Jump(GOTO, Label(23)),
      Label(14), VarOp(ALOAD, 3), Invoke(INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false), Jump(IFEQ, Label(23)),
      Label(19), Op(ICONST_1), Jump(GOTO, Label(26)),
      Label(23), Op(ICONST_0),
      Label(26), Op(IRETURN)))

    val t4t5 = List(
      VarOp(ALOAD, 1), Jump(IFNULL, Label(6)),
      VarOp(ALOAD, 1), Jump(IFNULL, Label(10)),
      Label(6), Op(ICONST_1), Jump(GOTO, Label(13)),
      Label(10), Op(ICONST_0),
      Label(13), Op(IRETURN))

    // t4: one side is known null, so just a null check on the other
    assertSameCode(getMethod(c, "t4"), t4t5)

    // t5: one side known null, so just a null check on the other
    assertSameCode(getMethod(c, "t5"), t4t5)

    // t6: no unnecessary GOTOs
    assertSameCode(getMethod(c, "t6"), List(
      VarOp(ILOAD, 1), IntOp(BIPUSH, 10), Jump(IF_ICMPNE, Label(7)),
      VarOp(ILOAD, 2), Jump(IFNE, Label(12)),
      Label(7), VarOp(ILOAD, 1), Op(ICONST_1), Jump(IF_ICMPEQ, Label(16)),
      Label(12), Op(ICONST_1), Jump(GOTO, Label(19)),
      Label(16), Op(ICONST_2),
      Label(19), Op(IRETURN)))

    // t7: universal equality
    assertInvoke(getMethod(c, "t7"), "scala/runtime/BoxesRunTime", "equals")

    // t8: no null checks invoking equals on modules and constants
    assertSameCode(getMethod(c, "t8"), List(
      Field(GETSTATIC, "scala/collection/immutable/Nil$", "MODULE$", "Lscala/collection/immutable/Nil$;"), VarOp(ALOAD, 1), Invoke(INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false), Jump(IFNE, Label(10)),
      Ldc(LDC, ""), VarOp(ALOAD, 1), Invoke(INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false), Jump(IFNE, Label(14)),
      Label(10), Op(ICONST_1), Jump(GOTO, Label(17)),
      Label(14), Op(ICONST_0),
      Label(17), Op(IRETURN)))
  }

  @Test // wrong local variable table for methods containing while loops
  def t9179(): Unit = {
    val code =
      """class C {
        |  def t(): Unit = {
        |    var x = ""
        |    while (x != null) {
        |      foo()
        |      x = null
        |    }
        |    bar()
        |  }
        |  def foo(): Unit = ()
        |  def bar(): Unit = ()
        |}
      """.stripMargin
    val c = compileClass(code)
    val t = getMethod(c, "t")
    val isFrameLine = (x: Instruction) => x.isInstanceOf[FrameEntry] || x.isInstanceOf[LineNumber]
    assertSameCode(t.instructions.filterNot(isFrameLine), List(
      Label(0), Ldc(LDC, ""), VarOp(ASTORE, 1),
      Label(4), VarOp(ALOAD, 1), Jump(IFNULL, Label(20)),
      Label(9), VarOp(ALOAD, 0), Invoke(INVOKEVIRTUAL, "C", "foo", "()V", false), Label(13), Op(ACONST_NULL), VarOp(ASTORE, 1), Label(17), Jump(GOTO, Label(4)),
      Label(20), VarOp(ALOAD, 0), Invoke(INVOKEVIRTUAL, "C", "bar", "()V", false), Label(25), Op(RETURN), Label(27)))
    val labels = t.instructions collect { case l: Label => l }
    val x = t.localVars.find(_.name == "x").get
    assertEquals(x.start, labels(1))
    assertEquals(x.end, labels(6))
  }

  @Test
  def sd186_traitLineNumber(): Unit = {
    val code =
      """trait T {
        |  def t(): Unit = {
        |    toString
        |    toString
        |  }
        |}
      """.stripMargin
    val t = compileClass(code)
    val tMethod = getMethod(t, "t$")
    @unused val invoke = Invoke(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false)
    // ths static accessor is positioned at the line number of the accessed method.
    assertSameCode(tMethod.instructions,
      List(Label(0), LineNumber(2, Label(0)), VarOp(ALOAD, 0), Invoke(INVOKESPECIAL, "T", "t", "()V", true), Op(RETURN), Label(4))
    )
  }

  @Test def `class constructor has correct line numbers (12470)`: Unit = {
    val code =
      """class A
        |class B
        |object D
        |class C
      """.stripMargin
    val lines = Map("A" -> 1, "B" -> 2, "D$" -> 3, "C" -> 4)
    compileClasses(code).foreach { c =>
      c.methods.asScala.foreach(m => convertMethod(m).instructions.foreach {
        case LineNumber(n, _) => assertEquals(s"class ${c.name} method ${m.name}", lines(c.name), n)
        case _ =>
      })
    }
  }

  @Test
  def sd233(): Unit = {
    val code = "def f = { println(1); synchronized(println(2)) }"
    val m = compileMethod(code)
    val List(ExceptionHandler(_, _, _, desc)) = m.handlers
    assert(desc == None, desc)
  }

  @Test
  def classesEndingInDollarHaveSignature(): Unit = {
    // A name-based test in the backend prevented classes ending in $ from getting a Scala signature
    val code = "class C$"
    val c = compileClass(code)
    assertEquals(c.attrs.asScala.toList.map(_.`type`).sorted, List("ScalaInlineInfo", "ScalaSig"))
  }

  @Test
  def t10343(): Unit = {
    val main = "class Main { Person() }"
    val person = "case class Person(age: Int = 1)"

    def check(code: String) = {
      val List(_, _, pm) = compileClasses(code)
      assertEquals(pm.name, "Person$")
      assertEquals(pm.methods.asScala.map(_.name).toList,
        // after typer, `"$lessinit$greater$default$1"` is next to `<init>`, but the constructor phase
        // and code gen change module constructors around. the second `apply` is a bridge, created in erasure.
        List("<clinit>", "$lessinit$greater$default$1", "toString", "apply", "apply$default$1", "unapply", "writeReplace", "apply", "<init>"))
    }
    check(s"$main\n$person")
    check(s"$person\n$main")
  }

  @Test
  def t11127(): Unit = {
    val code =
      """abstract class C {
        |  def b: Boolean
        |
        |  // no need to lift the `try` to a separate method if it's in the receiver expression
        |  def t1 = (try { Console } catch { case _: ClassCastException => Console }).println()
        |
        |  // no need to lift the `try`
        |  def t2 = !(try b catch { case _: ClassCastException => b })
        |
        |  def t3 = b || (try b catch { case _: ClassCastException => b })
        |
        |  def t4 = (try b catch { case _: ClassCastException => b }) || b
        |
        |  def t5 = b && (try b catch { case _: ClassCastException => b })
        |
        |  def t6 = (try b catch { case _: ClassCastException => b }) && b
        |
        |  def t7 = (try b catch { case _: ClassCastException => b }) && b || b && (try b catch { case _: ClassCastException => b || (try b catch { case _: ClassCastException => b }) })
        |}
      """.stripMargin
    val c = compileClass(code)
    def check(m: String, invoked: List[String]) = {
      val meth = getMethod(c, m)
      assert(meth.handlers.nonEmpty, meth.handlers)
      assertInvokedMethods(meth, invoked)
    }
    check("t1", List("scala/Console$.println"))
    check("t2", List("C.b", "C.b"))
    check("t3", List("C.b", "C.b", "C.b"))
    check("t4", List("C.b", "C.b", "C.b"))
    check("t5", List("C.b", "C.b", "C.b"))
    check("t6", List("C.b", "C.b", "C.b"))
    check("t7", List("C.b", "C.b", "C.b", "C.b", "C.b", "C.b", "C.b", "C.b"))
  }

  @Test
  def t11412(): Unit = {
    val code = "class A { val a = 0 }; class C extends A with App { val x = 1; val y = x }"
    val cs = compileClasses(code)
    val c = cs.find(_.name == "C").get
    val fs = c.fields.asScala.toList.sortBy(_.name).map(f => (f.name, (f.access & Opcodes.ACC_FINAL) != 0))
    assertEquals(List(
      ("executionStart", false),
      ("scala$App$$_args", false),
      ("scala$App$$initCode", false),
      ("x", false),
      ("y", false)
    ), fs)
    val assignedInConstr = getMethod(c, "<init>").instructions.filter(_.opcode == Opcodes.PUTFIELD)
    assertEquals(Nil, assignedInConstr)
  }

  @Test
  def t11412b(): Unit = {
    val code = "class C { def f = { var x = 0; val y = 1; class K extends App { def m = x + y } } }"
    val cs = compileClasses(code)
    val k = cs.find(_.name == "C$K$1").get
    val fs = k.fields.asScala.toList.sortBy(_.name).map(f => (f.name, (f.access & Opcodes.ACC_FINAL) != 0))
    assertEquals(List(
      ("$outer", true),
      ("executionStart", false),
      ("scala$App$$_args", false),
      ("scala$App$$initCode", false),
      ("x$1", true), // captured, assigned in constructor
      ("y$1", true)  // captured
    ), fs)
    val assignedInConstr = getMethod(k, "<init>").instructions.filter(_.opcode == Opcodes.PUTFIELD) map {
      case f: Field => f.name
      case _ => ???  // @unchecked
    }
    assertEquals(List("$outer", "x$1", "y$1"), assignedInConstr.sorted)
  }

  @Test
  def t11641(): Unit = {
    val code =
      """class B { val b = 0 }
        |class C extends DelayedInit {
        |  def delayedInit(body: => Unit): Unit = ()
        |}
        |class D extends DelayedInit {
        |  val d = 0
        |  def delayedInit(body: => Unit): Unit = ()
        |}
        |class E extends C {
        |  val e = 0
        |}
        |class F extends D
      """.stripMargin
    val cs = compileClasses(code, allowMessage = _.msg.contains("2 deprecations"))

    assertDoesNotInvoke(getMethod(cs.find(_.name == "B").get, "<init>"), "releaseFence")
    assertDoesNotInvoke(getMethod(cs.find(_.name == "C").get, "<init>"), "releaseFence")
    assertInvoke(getMethod(cs.find(_.name == "D").get, "<init>"), "scala/runtime/Statics", "releaseFence")
    assertInvoke(getMethod(cs.find(_.name == "E").get, "<init>"), "scala/runtime/Statics", "releaseFence")
    assertDoesNotInvoke(getMethod(cs.find(_.name == "F").get, "<init>"), "releaseFence")
  }

  @Test
  def t11718(): Unit = {
    val code = """class A11718 { private val a = ""; lazy val b = a }"""
    val cs = compileClasses(code)
    val A = cs.find(_.name == "A11718").get
    val a = A.fields.asScala.find(_.name == "a").get
    assertEquals(0, a.access & Opcodes.ACC_FINAL)
  }

  @Test
  def t12362(): Unit = {
    val code                 =
      """object Test {
        |  def foo(value: String) = {
        |    println(value)
        |  }
        |
        |  def abcde(value1: String, value2: Long, value3: Double, value4: Int, value5: Double): Double = {
        |    println(value1)
        |    value5
        |  }
        |}""".stripMargin

    val List(mirror, _) = compileClasses(code)
    assertEquals(mirror.name, "Test")

    val foo    = getAsmMethod(mirror, "foo")
    val abcde  = getAsmMethod(mirror, "abcde")

    def t(m: MethodNode, r: List[(String, String, Int)]) = {
      assertTrue((m.access & Opcodes.ACC_STATIC) != 0)
      assertEquals(r, m.localVariables.asScala.toList.map(l => (l.desc, l.name, l.index)))
    }

    t(foo, List(("Ljava/lang/String;", "value", 0)))
    t(abcde, List(("Ljava/lang/String;", "value1", 0), ("J", "value2", 1), ("D", "value3", 3), ("I", "value4", 5), ("D", "value5", 6)))
  }

  @Test
  def nonSpecializedValFence(): Unit = {
    def code(u1: String) =
      s"""abstract class Speck[@specialized(Int) T](t: T) {
         |  val a = t
         |  $u1
         |  lazy val u2 = "?"
         |  var u3 = "?"
         |  val u4: String
         |  var u5: String
         |}
         |""".stripMargin

    for (u1 <- "" :: List("", "private", "private[this]", "protected").map(mod => s"$mod val u1 = \"?\"")) {
      for (c <- compileClasses(code(u1)).map(getMethod(_, "<init>")))
        if (u1.isEmpty)
          assertDoesNotInvoke(c, "releaseFence")
        else
          assertInvoke(c, "scala/runtime/Statics", "releaseFence")
    }
  }
}
