/*
 * scala-exercises-server
 * Copyright (C) 2015-2016 47 Degrees, LLC. <http://www.47deg.com>
 */

package com.fortysevendeg.exercises.services.free

import com.fortysevendeg.exercises.persistence.domain.SaveUserProgress
import com.fortysevendeg.exercises.persistence.repositories.UserProgressRepository
import com.fortysevendeg.shared.free.ExerciseOps
import doobie.imports.Transactor
import shared._

import cats.free.Free
import cats.free.Inject
import cats.std.list._

import scalaz.concurrent.Task

/** Users Progress Ops GADT
  */
sealed trait UserProgressOp[A]

final case class UpdateUserProgress(
  userProgress: SaveUserProgress.Request
) extends UserProgressOp[UserProgress]

/** Exposes User Progress operations as a Free monadic algebra that may be combined with other Algebras via
  * Coproduct
  */
class UserProgressOps[F[_]](implicit I: Inject[UserProgressOp, F], EO: ExerciseOps[F], UPR: UserProgressRepository, DBO: DBOps[F], T: Transactor[Task]) {

  def saveUserProgress(userProgress: SaveUserProgress.Request): Free[F, UserProgress] =
    Free.inject[UserProgressOp, F](UpdateUserProgress(userProgress))

  def fetchMaybeUserProgress(user: Option[User]): Free[F, OverallUserProgress] = {
    user.fold(anonymousUserProgress)(fetchUserProgress)
  }

  private[this] def anonymousUserProgress: Free[F, OverallUserProgress] =
    EO.getLibraries.map({ libs ⇒
      val libraries = libs.map(l ⇒ {
        OverallUserProgressItem(
          libraryName = l.name,
          sections = 0,
          totalSections = l.sections.size,
          completed = false
        )
      })
      OverallUserProgress(libraries = libraries)
    })

  def fetchUserProgress(user: User): Free[F, OverallUserProgress] = {
    import ConnectionIOOps._
    for {
      lbs ← UPR.findByUserIdAggregated(user.id).liftF[F]
      libNames = lbs.map(_._1)
      completedSectionsByLibrary ← Free.freeMonad[F].sequence(
        libNames map { name ⇒ UPR.completedSectionsByLibrary(user.id, name).liftF[F] }
      ).map(counts ⇒ (libNames zip counts).toMap)
      items = lbs map {
        case (libraryName, _, succeeded) ⇒
          EO
            .getLibrary(libraryName)
            .map(_ map (_.sections.size) getOrElse 0)
            .map { totalSections ⇒
              val completedSections = completedSectionsByLibrary.get(libraryName).getOrElse(0)
              OverallUserProgressItem(
                libraryName = libraryName,
                sections = completedSections,
                totalSections = totalSections,
                completed = succeeded && completedSections == totalSections
              )
            }
      }
      list ← Free.freeMonad[F].sequence(items)
    } yield OverallUserProgress(list)
  }

  def fetchMaybeUserProgressByLibrary(user: Option[User], libraryName: String): Free[F, LibrarySections] = {
    user.fold(anonymousUserProgressByLibrary(libraryName))(fetchUserProgressByLibrary(_, libraryName))
  }

  private[this] def anonymousUserProgressByLibrary(libraryName: String): Free[F, LibrarySections] = {
    EO.getLibrary(libraryName).map(_.map { lib ⇒
      LibrarySections(
        libraryName = lib.name,
        sections = lib.sections.map(s ⇒ {
          SectionInfoItem(
            sectionName = s.name,
            succeeded = false
          )
        })
      )
    }.getOrElse(LibrarySections(libraryName, Nil)))
  }

  def fetchUserProgressByLibrary(user: User, libraryName: String): Free[F, LibrarySections] = {
    import ConnectionIOOps._
    for {
      maybeLibrary ← EO.getLibrary(libraryName)
      sectionProgress ← UPR.findUserProgressByLibrary(user, libraryName).liftF[F]
      librarySections = maybeLibrary.fold(Nil: List[shared.Section])(_.sections)
      sections = librarySections.map(s ⇒ {
        val maybeSectionProgress = sectionProgress.find(_.sectionName == s.name)
        maybeSectionProgress.getOrElse(
          SectionInfoItem(sectionName = s.name, succeeded = false)
        )
      })
    } yield LibrarySections(libraryName, sections)
  }

  def fetchUserProgressByLibrarySection(
    user:        User,
    libraryName: String,
    sectionName: String
  ): Free[F, LibrarySectionArgs] = {
    import ConnectionIOOps._
    for {
      lbs ← UPR.findUserProgressBySection(user, libraryName, sectionName).liftF[F]
      libraryInformation ← EO.getLibrary(libraryName) map extractLibraryInformation(sectionName)
      (sectionsTotal, sectionExerciseList) = libraryInformation
      eList = calculateExerciseList(lbs.exerciseList, sectionExerciseList)
    } yield LibrarySectionArgs(
      libraryName = libraryName,
      totalSections = sectionsTotal,
      exercises = eList,
      librarySucceeded = lbs.succeeded && lbs.exerciseList.size == sectionExerciseList.size
    )
  }

  private[this] def extractLibraryInformation(sectionName: String): (Option[Library]) ⇒ (Int, List[Exercise]) = {
    _ map (fetchSectionInformation(_, sectionName)) getOrElse (0, Nil)
  }

  private[this] def fetchSectionInformation(library: Library, sectionName: String): (Int, List[Exercise]) = {
    val sectionNumbers = library.sections.size
    val exerciseList = library.sections.find(_.name == sectionName) map (_.exercises) getOrElse Nil
    (sectionNumbers, exerciseList)
  }

  private[this] def calculateExerciseList(
    userProgressExerciseList: List[LibrarySectionExercise],
    allExercises:             List[Exercise]
  ): List[LibrarySectionExercise] = {
    val notStartedExercises: List[Exercise] =
      allExercises
        .filterNot(exercise ⇒
          userProgressExerciseList.exists(_.methodName == exercise.method))
    val mappedExercises =
      notStartedExercises map { nee ⇒
        LibrarySectionExercise(methodName = nee.method, args = Nil, succeeded = false)
      }
    userProgressExerciseList ::: mappedExercises
  }
}

/** Default implicit based DI factory from which instances of the UserOps may be obtained
  */
object UserProgressOps {

  implicit def instance[F[_]](implicit I: Inject[UserProgressOp, F], EO: ExerciseOps[F], UPR: UserProgressRepository, DBO: DBOps[F], T: Transactor[Task]): UserProgressOps[F] = new UserProgressOps[F]

}
