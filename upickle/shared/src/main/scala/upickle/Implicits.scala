package upickle


import upickle.Js.Value

import scala.reflect.ClassTag
import scala.concurrent.duration.{FiniteDuration, Duration}
import acyclic.file
import scala.language.higherKinds
import scala.language.experimental.macros
import java.util.UUID


/**
* Typeclasses to allow read/writing of all the common
* data-types and data-structures in the standard library
*/
trait Implicits extends Types { imp: Generated =>



  import Aliases._
  // We need these guys, but they'd be generated by the `Generated`
  // code, which is mixed in together with this fellow
  implicit def Tuple2R[T1: R, T2: R]: R[(T1, T2)]
  implicit def Tuple2W[T1: W, T2: W]: W[(T1, T2)]

  /**
   * APIs that need to be exposed to the outside world to support Macros
   * which depend on them, but probably should not get used directly.
   */
  object Internal {
    type Reader[T] = Implicits.this.Reader[T]
    type Writer[T] = Implicits.this.Writer[T]
    def makeReader[T](pf: PartialFunction[Js.Value, T]) = imp.Reader(pf)
    def makeWriter[T](f: T => Js.Value) = imp.Writer(f)
    // Have to manually spell out the implicit here otherwise compiler crashes
    def readJs[T](expr: Js.Value)(implicit ev: Reader[T]): T = imp.readJs(expr)(ev)
    def writeJs[T](expr: T)(implicit ev: Writer[T]): Js.Value = imp.writeJs(expr)(ev)

    def merge0[T: ClassTag, R, U](f: T => R): U => R = {
      case t: T => f(t)
    }

    def merge[T: ClassTag, R, V: ClassTag, U](f: T => R, g: V => R): U => R = {
      case v: V => g(v)
      case t: T => f(t)
    }

    def validate[T](name: String)(pf: PartialFunction[Js.Value, T]) = new PartialFunction[Js.Value, T] {
      def isDefinedAt(x: Value) = pf.isDefinedAt(x)

      def apply(v1: Value): T = pf.applyOrElse(v1, (x: Js.Value) => throw Invalid.Data(x, name))
    }
    def validateReader[T](name: String)(r: => Reader[T]): Reader[T] = new Reader[T]{
      override def read0 = validate(name)(r.read)
    }
  }

  import Internal._

  def Case0R[T](t: () => T) = R[T]({case x => t()})
  def Case0W[T](f: T => Boolean) = W[T](x => Js.Obj())
  def SingletonR[T](t: T) = R[T]({case x => t})
  def SingletonW[T](f: T) = W[T](x => Js.Obj())

  private[this] type JPF[T] = PartialFunction[Js.Value, T]
  private[this] val booleanReaderFunc: JPF[Boolean] = Internal.validate("Boolean"){
    case Js.True => true
    case Js.False => false
  }
  implicit val BooleanRW = RW[Boolean](
    if (_) Js.True else Js.False,
    booleanReaderFunc
  )
  implicit val UnitRW = RW[Unit](
    _ => Js.Obj(),
    {case _ => ()}
  )

  def CaseR[T: R, V](f: T => V, names: Array[String], defaults: Array[Js.Value]): Reader[V] = {
    Reader {
      case js: Js.Obj => f(implicitly[R[T]].read(this.mapToArray(js, names, defaults)))
    }
  }
  def CaseW[T: W, V](f: V => Option[T], names: Array[String], defaults: Array[Js.Value]): Writer[V] = {
    Writer{
      t: V =>
        this.arrayToMap(implicitly[W[T]].write(f(t).get).asInstanceOf[Js.Arr], names, defaults)
    }
  }

  private[this] def numericStringReaderFunc[T](func: String => T): JPF[T] = Internal.validate("String"){
    case x: Js.Str => func(x.value)
  }
  private[this] def NumericStringReadWriter[T](func: String => T) = RW[T](
    x => Js.Str(x.toString),
    numericStringReaderFunc[T](func)
  )
  private[this] def numericReaderFunc[T: Numeric](func: Double => T, func2: String => T): JPF[T] = Internal.validate("Number or String"){
    case n @ Js.Num(x) => try{func(x) } catch {case e: NumberFormatException => throw Invalid.Data(n, "Number")}
    case s @ Js.Str(x) => try{func2(x) } catch {case e: NumberFormatException => throw Invalid.Data(s, "Number")}
  }

  private[this] def NumericReadWriter[T: Numeric](func: Double => T, func2: String => T): RW[T] = RW[T](
   {
      case x @ Double.PositiveInfinity => Js.Str(x.toString)
      case x @ Double.NegativeInfinity => Js.Str(x.toString)
      case x: Double if java.lang.Double.isNaN(x) => Js.Str(x.toString)
      case x: Float if java.lang.Double.isNaN(x) => Js.Str(x.toString)
      case x => Js.Num(implicitly[Numeric[T]].toDouble(x))
    },
    numericReaderFunc[T](func, func2)
  )
  private[this] val stringReaderFunc: JPF[String] = Internal.validate("String"){
    case x: Js.Str => x.value
  }
  implicit val StringRW = RW[String](Js.Str, stringReaderFunc)

  private[this] val symbolReaderFunc: JPF[Symbol] = Internal.validate("Symbol"){
    case x: Js.Str => Symbol(x.value)
  }
  implicit val SymbolRW = RW[Symbol](
    x => Js.Str(x.toString().substring(1)),
    symbolReaderFunc
  )

  implicit val CharRW = NumericStringReadWriter[Char](_(0))
  implicit val ByteRW = NumericReadWriter(_.toByte, _.toByte)
  implicit val ShortRW = NumericReadWriter(_.toShort, _.toShort)
  implicit val IntRW = NumericReadWriter(_.toInt, _.toInt)
  implicit val LongRW = NumericStringReadWriter[Long](_.toLong)
  implicit val FloatRW = NumericReadWriter(_.toFloat, _.toFloat)
  implicit val DoubleRW = NumericReadWriter(_.toDouble, _.toDouble)

  import collection.generic.CanBuildFrom
  implicit def SeqishR[V[_], T]
                       (implicit cbf: CanBuildFrom[Nothing, T, V[T]], r: R[T]): R[V[T]] = R[V[T]](
    Internal.validate("Array(n)"){case Js.Arr(x@_*) => x.map(readJs[T]).to[V]}
  )

  implicit def SeqishW[T, V[_]](implicit v: V[_] <:< Iterable[_], w: W[T]): W[V[T]] = W[V[T]]{
    (x: V[T]) => Js.Arr(x.iterator.asInstanceOf[Iterator[T]].map(writeJs(_)).toArray:_*)
  }

  private[this] def SeqLikeW[T: W, V[_]](g: V[T] => Option[Seq[T]]): W[V[T]] = W[V[T]](
    x => Js.Arr(g(x).get.map(x => writeJs(x)):_*)
  )
  private[this] def SeqLikeR[T: R, V[_]](f: Seq[T] => V[T]): R[V[T]] = R[V[T]](
    Internal.validate("Array(n)"){case Js.Arr(x@_*) => f(x.map(readJs[T]))}
  )

  implicit def OptionW[T: W]: W[Option[T]] = SeqLikeW[T, Option](x => Some(x.toSeq))
  implicit def SomeW[T: W] = W[Some[T]](OptionW[T].write)
  implicit def NoneW: W[None.type] = W[None.type](OptionW[Int].write)
  implicit def OptionR[T: R]: R[Option[T]] = SeqLikeR[T, Option](_.headOption)
  implicit def SomeR[T: R] = R[Some[T]](OptionR[T].read andThen (_.asInstanceOf[Some[T]]))
  implicit def NoneR: R[None.type] = R[None.type](OptionR[Int].read andThen (_.asInstanceOf[None.type]))

  implicit def ArrayW[T: W] = SeqLikeW[T, Array](Array.unapplySeq)
  implicit def ArrayR[T: R: ClassTag] = SeqLikeR[T, Array](x => Array.apply(x:_*))

  implicit def MapW[K: W, V: W]: W[Map[K, V]] =
    if (implicitly[W[K]] == implicitly[W[String]])
      W[Map[K, V]](x => Js.Obj(x.toSeq.map{ case (k, v) => (k.asInstanceOf[String], writeJs[V](v))}: _*))
    else
      W[Map[K, V]](x => Js.Arr(x.toSeq.map(writeJs[(K, V)]): _*))


  implicit def MapR[K: R, V: R]: R[Map[K, V]] =
    if (implicitly[R[K]] == implicitly[R[String]])
      R[Map[K, V]](Internal.validate("Object"){
        case x: Js.Obj => x.value.map{case (k, v) => (k.asInstanceOf[K], readJs[V](v))}.toMap
      })
    else
      R[Map[K, V]](Internal.validate("Array(n)"){
        case x: Js.Arr => x.value.map(readJs[(K, V)]).toMap
      })

  implicit def EitherR[A: R, B: R]: R[Either[A, B]] = R[Either[A, B]](
    RightR[A, B].read orElse LeftR[A, B].read
  )
  implicit def RightR[A: R, B: R]: R[Right[A, B]] = R[Right[A, B]] {
    case Js.Arr(Js.Num(1), x) => Right(readJs[B](x))
  }
  implicit def LeftR[A: R, B: R]: R[Left[A, B]] = R[Left[A, B]] {
    case Js.Arr(Js.Num(0), x) => Left(readJs[A](x))
  }

  implicit def RightW[A: W, B: W]: W[Right[A, B]] = W[Right[A, B]](EitherW[A, B].write)

  implicit def LeftW[A: W, B: W]: W[Left[A, B]] = W[Left[A, B]](EitherW[A, B].write)

  implicit def EitherW[A: W, B: W]: W[Either[A, B]] = W[Either[A, B]]{
    case Left(t) => Js.Arr(Js.Num(0), writeJs(t))
    case Right(t) => Js.Arr(Js.Num(1), writeJs(t))
  }
  implicit val DurationW: W[Duration] = W[Duration]{
    case Duration.Inf => writeJs("inf")
    case Duration.MinusInf => writeJs("-inf")
    case x if x eq Duration.Undefined => writeJs("undef")
    case x => writeJs(x.toNanos)
  }

  implicit val InfiniteW = W[Duration.Infinite](DurationW.write)
  implicit val InfiniteR = R[Duration.Infinite]{Internal.validate("DurationString"){
    case Js.Str("inf") => Duration.Inf
    case Js.Str("-inf") => Duration.MinusInf
    case Js.Str("undef") => Duration.Undefined
  }}

  implicit val FiniteW = W[FiniteDuration](DurationW.write)
  implicit val FiniteR = R[FiniteDuration]{Internal.validate("DurationString"){
    case x: Js.Str => Duration.fromNanos(x.value.toLong)
  }}

  implicit val DurationR = R[Duration](Internal.validate("DurationString"){
    FiniteR.read orElse InfiniteR.read
  })

  implicit def UuidR: R[UUID] = R[UUID]{case Js.Str(s) => UUID.fromString(s)}
  implicit def UuidW: W[UUID] = W[UUID]{case t: UUID => Js.Str(t.toString)}



  def makeReader[T](pf: PartialFunction[Js.Value, T]) = Reader.apply(pf)
  def makeWriter[T](f: T => Js.Value): Writer[T] = Writer.apply(f)

}
