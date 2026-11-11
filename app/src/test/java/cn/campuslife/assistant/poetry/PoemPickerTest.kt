package cn.campuslife.assistant.poetry
import org.junit.Assert.*
import org.junit.Test
class PoemPickerTest {
 @Test fun avoidsCurrentAndRecentWheneverPossible(){assertEquals("c",PoemPicker.choose(listOf("a","b","c"),"a",listOf("b")){0})}
 @Test fun fullHistoryNeverForcesImmediateRepeat(){repeat(20){assertNotEquals("a",PoemPicker.choose(listOf("a","b"),"a",listOf("a","b")){it % 1})}}
 @Test fun unknownHistoryDoesNotBreakSelection(){assertEquals("b",PoemPicker.choose(listOf("a","b"),"a",listOf("removed")){0})}
 @Test fun singletonAndEmptyHaveExplicitBehavior(){assertEquals("a",PoemPicker.choose(listOf("a"),"a",emptyList()){0});assertThrows(IllegalArgumentException::class.java){PoemPicker.choose(emptyList(),null,emptyList()){0}}}
 @Test fun sourceHasNoDecorativeQuotes(){assertEquals("李白 · 行路难",Poem("p","长风破浪会有时，直挂云帆济沧海。","李白","行路难").credit)}
}
