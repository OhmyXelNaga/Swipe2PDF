package com.example.swipe2pdf

import org.junit.Assert.*
import org.junit.Test

class CoreLogicTest {
    @Test fun pageCountHasOneLessSwipe(){assertEquals(0,CapturePlan.swipesFor(1));assertEquals(9,CapturePlan.swipesFor(10));assertEquals(998,CapturePlan.swipesFor(999))}
    @Test(expected=IllegalArgumentException::class) fun pageCountRejectsZero(){CapturePlan.swipesFor(0)}
    @Test fun stateTransitions(){val s=StateMachine();s.move(JobState.Preparing);s.move(JobState.Capturing);s.move(JobState.Paused);s.move(JobState.Capturing);s.move(JobState.Processing);s.move(JobState.Completed);assertEquals(JobState.Completed,s.state)}
    @Test(expected=IllegalArgumentException::class) fun invalidStateTransition(){StateMachine().move(JobState.Completed)}
    @Test fun normalizedCoordinateConversion(){assertEquals(android.graphics.Rect(100,100,900,1900),NormalizedRect(.1f,.05f,.9f,.95f).pixels(1000,2000))}
    @Test fun cropValidation(){assertTrue(NormalizedRect(0f,0f,1f,1f).valid());assertFalse(NormalizedRect(.8f,0f,.2f,1f).valid());assertTrue(NormalizedRect(0f,0f,.5f,1f).pixels(100,100).right==50)}
    @Test fun uniqueKoreanFilename(){assertEquals("강의.pdf",ImageAndPdf.uniqueName(emptySet(),"강의"));assertEquals("강의 (3).pdf",ImageAndPdf.uniqueName(setOf("강의.pdf","강의 (2).pdf"),"강의.pdf"))}
    @Test fun ordering(){assertEquals(listOf("b","a","c"),reorder(listOf("a","b","c"),1,0));assertEquals(listOf("a","c","b"),reorder(listOf("a","b","c"),1,2))}
}
