/**
  * Copyright: [2011] Ben Lever
  */
package com.nicta.scoobi


/* Execution plan intermediate representation. */
object AST {

  object Id extends UniqueInt
  object RollingInt extends UniqueInt

  /** Intermediate representation - closer aligned to actual MSCR contetns. */
  sealed abstract class Node[A] {
    val id = Id.get
  }


  /** Input channel mapper that is not hooked up to a GBK. */
  case class Mapper[A : Manifest : HadoopWritable,
                    B : Manifest : HadoopWritable]
      (in: Node[A],
       f: A => Iterable[B])
    extends Node[B] with MapperLike[A, Int, B] {

    def mkTaggedMapper(tags: Set[Int]) = new TaggedMapper[A, Int, B](tags) {
      /* The output key will be an integer that is continually incrementing. This will ensure
       * the mapper produces an even distribution of key values. */
      def map(value: A): Iterable[(Int, B)] = f(value).map((x: B) => (RollingInt.get, x))
    }

    override def toString = "Mapper" + id

  }


  /** Input channel mapper that is hooked up to a GBK. */
  case class GbkMapper[A : Manifest : HadoopWritable,
                       K : Manifest : HadoopWritable : Ordering,
                       V : Manifest : HadoopWritable]
      (in: Node[A],
       f: A => Iterable[(K, V)])
    extends Node[(K, V)] with MapperLike[A, K, V] {

    /** */
    def mkTaggedMapper(tags: Set[Int]) = new TaggedMapper[A, K, V](tags) {
      def map(value: A): Iterable[(K, V)] = f(value)
    }

    override def toString = "GbkMapper" + id

  }


  /** Combiner. */
  case class Combiner[K, V]
      (in: Node[(K, Iterable[V])],
       f: (V, V) => V)
      (implicit mK:  Manifest[K], wtK: HadoopWritable[K], ordK: Ordering[K],
                mV:  Manifest[V], wtV: HadoopWritable[V],
                mKV: Manifest[(K, V)], wtKV: HadoopWritable[(K, V)])
    extends Node[(K, V)] with CombinerLike[V] with ReducerLike[K, V, (K, V)] {

    def mkTaggedCombiner(tag: Int) = new TaggedCombiner[V](tag) {
      def combine(x: V, y: V): V = f(x, y)
    }

    def mkTaggedReducer(tag: Int) = new TaggedReducer[K, V, (K, V)](tag)(mK, wtK, ordK, mV, wtV, mKV, wtKV) {
      def reduce(key: K, values: Iterable[V]): Iterable[(K, V)] = {
          List((key, values.tail.foldLeft(values.head)(f)))
      }
    }

    /** Produce a TaggedReducer using this combiner function and an additional reducer function. */
    def mkTaggedReducerWithCombiner[B](tag: Int, rf: ((K, V)) => Iterable[B])(implicit mB: Manifest[B], wtB: HadoopWritable[B]) =
      new TaggedReducer[K, V, B](tag)(mK, wtK, ordK, mV, wtV, mB, wtB) {
        def reduce(key: K, values: Iterable[V]): Iterable[B] = {
          rf((key, values.tail.foldLeft(values.head)(f)))
        }
      }

    override def toString = "Combiner" + id

  }


  /** GbkReducer - a reduce (i.e. FlatMap) that follows a GroupByKey (i.e. no Combiner). */
  case class GbkReducer[K : Manifest : HadoopWritable : Ordering,
                        V : Manifest : HadoopWritable,
                        B : Manifest : HadoopWritable]
      (in: Node[(K, Iterable[V])],
       f: ((K, Iterable[V])) => Iterable[B])
    extends Node[B] with ReducerLike[K, V, B] {

    def mkTaggedReducer(tag: Int) = new TaggedReducer[K, V, B](tag) {
      def reduce(key: K, values: Iterable[V]): Iterable[B] = f((key, values))
    }

    override def toString = "GbkReducer" + id

  }


  /** Reducer - a reduce (i.e. FlatMap) that follows a Combiner. */
  case class Reducer[K : Manifest : HadoopWritable : Ordering,
                     V : Manifest : HadoopWritable,
                     B : Manifest : HadoopWritable]
      (in: Node[(K, V)],
       f: ((K, V)) => Iterable[B])
    extends Node[B] with ReducerLike[K, V, B] {

    /* It is expected that this Reducer is preceeded by a Combiner. */
    def mkTaggedReducer(tag: Int) = in match {
      case c@Combiner(_, _) => c.mkTaggedReducerWithCombiner(tag, f)
      case _                => error("Reducer must be preceeded by Combiner")
    }

    override def toString = "Reducer" + id

  }


  /** Usual Load node. */
  case class Load[A] extends Node[A] {
    override def toString = "Load" + id

  }


  /** Usual Flatten node. */
  case class Flatten[A : Manifest : HadoopWritable](ins: List[Node[A]]) extends Node[A] {
    override def toString = "Flatten" + id

  }


  /** Usual GBK node. */
  case class GroupByKey[K : Manifest : HadoopWritable : Ordering,
                        V : Manifest : HadoopWritable]
      (in: Node[(K, V)])
    extends Node[(K, Iterable[V])] {

    override def toString = "GroupByKey" + id

  }


  /** Apply a function to each node of the AST (visit each node only once). */
  def eachNode[U](starts: Set[Node[_]])(f: Node[_] => U): Unit = {
    starts foreach { visitOnce(_, f, Set()) }

    def visitOnce(node: Node[_], f: Node[_] => U, visited: Set[Node[_]]): Unit = {
      if (!visited.contains(node)) {
        node match {
          case Mapper(n, _)     => visitOnce(n, f, visited + node); f(node)
          case GbkMapper(n, _)  => visitOnce(n, f, visited + node); f(node)
          case Combiner(n, _)   => visitOnce(n, f, visited + node); f(node)
          case GbkReducer(n, _) => visitOnce(n, f, visited + node); f(node)
          case Reducer(n, _)    => visitOnce(n, f, visited + node); f(node)
          case GroupByKey(n)    => visitOnce(n, f, visited + node); f(node)
          case Flatten(ns)      => ns.foreach{visitOnce(_, f, visited + node)}; f(node)
          case Load()           => f(node)
        }
      }
    }
  }
}
