package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class AppearanceSelectionTest {
 @Test fun everyLegacyStyleKeepsItsOriginalShapeAndDayNightPalette(){
  UiStyle.entries.forEach{old->
   val restored=AppearanceSelection.restore(null,null,old.name)
   assertEquals(old.name,restored.shape.name)
   assertEquals(old.radius,restored.shape.radius,0f)
   listOf(false,true).forEach{dark->assertEquals(visualPalette(old,dark),visualPalette(restored.palette,dark))}
  }
 }
 @Test fun independentlySavedCombinationTakesPrecedenceOverLegacyStyle(){
  val restored=AppearanceSelection.restore("JOURNAL","DUT","AURORA")
  assertEquals(AppearanceSelection(UiShape.JOURNAL,UiColorPalette.DUT),restored)
 }
 @Test fun partialMigrationOnlyFillsTheMissingAxis(){
  assertEquals(AppearanceSelection(UiShape.COAST,UiColorPalette.AURORA),AppearanceSelection.restore("COAST",null,"AURORA"))
  assertEquals(AppearanceSelection(UiShape.JOURNAL,UiColorPalette.IRIS),AppearanceSelection.restore(null,"IRIS","JOURNAL"))
 }
 @Test fun missingOrRetiredSettingsHaveStableDefaults(){
  assertEquals(AppearanceSelection(UiShape.GEEK,UiColorPalette.DUT),AppearanceSelection.restore(null,null,null))
  assertEquals(AppearanceSelection(UiShape.GEEK,UiColorPalette.DUT),AppearanceSelection.restore("REMOVED","REMOVED","BLOCK"))
 }
 @Test fun switchingShapesNeverChangesTheChosenPalette(){
  val original=AppearanceSelection(UiShape.JOURNAL,UiColorPalette.COAST)
  UiShape.entries.forEach{shape->
   val changed=original.updated(shape=shape)
   assertEquals(shape,changed.shape)
   assertEquals(original.palette,changed.palette)
   listOf(false,true).forEach{dark->assertEquals(visualPalette(original.palette,dark),visualPalette(changed.palette,dark))}
  }
 }
 @Test fun switchingPalettesNeverChangesShapeTypographyOrGeometry(){
  val original=AppearanceSelection(UiShape.AURORA,UiColorPalette.DUT)
  UiColorPalette.entries.forEach{palette->
   val changed=original.updated(palette=palette)
   assertEquals(original.shape,changed.shape)
   assertEquals(original.shape.radius,changed.shape.radius,0f)
   assertEquals(palette,changed.palette)
  }
 }
 @Test fun savedCombinationSurvivesReloadWithoutReapplyingLegacyDefaults(){
  val changed=AppearanceSelection.restore(null,null,"AURORA").updated(shape=UiShape.JOURNAL,palette=UiColorPalette.DUT)
  assertEquals(changed,AppearanceSelection.restore(changed.shape.name,changed.palette.name,"AURORA"))
 }
 @Test fun assistantCanUseLabelsOrStableNamesButCannotInventAnOption(){
  assertEquals(UiShape.JOURNAL,UiShape.parse("纸页"))
  assertEquals(UiShape.JOURNAL,UiShape.parse("journal"))
  assertEquals(UiColorPalette.DUT,UiColorPalette.parse("大工蓝"))
  assertEquals(UiColorPalette.DUT,UiColorPalette.parse("dut"))
  assertThrows(IllegalStateException::class.java){UiShape.parse("不存在")}
  assertThrows(IllegalStateException::class.java){UiColorPalette.parse("不存在")}
 }
}
