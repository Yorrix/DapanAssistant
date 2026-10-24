package cn.dapan.assistant

import androidx.compose.ui.geometry.Offset

/** No zoom/collision culling or displacement from geographic anchors.
 * Off-screen geometry is clipped by Canvas, never pinned to screen edges. */
internal fun a01LabelPositions(anchors:Map<String,Offset>):Map<String,Offset> =
 listOf("A01-N","A01-S","A01-W","A01-E").mapNotNull{name->anchors[name]?.let{name to it}}.toMap()
