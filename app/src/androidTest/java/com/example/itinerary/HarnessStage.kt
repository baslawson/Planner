package com.example.itinerary

/**
 * One step of a check that needs something done from outside between steps (force-stopping the app, or passing a
 * fixture argument). Left out of normal runs by the `notAnnotation` runner argument in app/build.gradle.kts; run a step
 * on its own with `adb shell am instrument -e class <Class>#<method> …`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class HarnessStage
