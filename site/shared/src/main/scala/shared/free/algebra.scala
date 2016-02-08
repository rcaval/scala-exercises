package com.fortysevendeg.shared.free

import scala.language.higherKinds

import cats.data.Xor
import cats.free.Free
import cats.free.Inject
import shared.ExerciseEvaluation
import shared.Library
import shared.Section

/** Exercise Ops GADT
  */
sealed trait ExerciseOp[A]
final case class GetLibraries() extends ExerciseOp[List[Library]]
final case class GetSection(libraryName: String, sectionName: String) extends ExerciseOp[Option[Section]]
final case class Evaluate(exerciseEvaluation: ExerciseEvaluation) extends ExerciseOp[Throwable Xor Unit]

/** Exposes Exercise operations as a Free monadic algebra that may be combined with other Algebras via
  * Coproduct
  */
class ExerciseOps[F[_]](implicit I: Inject[ExerciseOp, F]) {

  def getLibraries: Free[F, List[Library]] =
    Free.inject[ExerciseOp, F](GetLibraries())

  def getSection(libraryName: String, sectionName: String): Free[F, Option[Section]] =
    Free.inject[ExerciseOp, F](GetSection(libraryName, sectionName))

  def evaluate(evaluation: ExerciseEvaluation): Free[F, Throwable Xor Unit] =
    Free.inject[ExerciseOp, F](Evaluate(evaluation))

}

/** Default implicit based DI factory from which instances of the ExerciseOps may be obtained
  */
object ExerciseOps {

  implicit def instance[F[_]](implicit I: Inject[ExerciseOp, F]): ExerciseOps[F] = new ExerciseOps[F]

}
