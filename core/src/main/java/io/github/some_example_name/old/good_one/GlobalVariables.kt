package io.github.some_example_name.old.good_one

import com.badlogic.gdx.audio.Sound
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.substances.Substance

//Потом наверно так не будет, а пока пойдет

var time: Float = 0f
//val cells = mutableListOf<Cell>()
//val links = mutableListOf<Link>()
//val neuronLinks = mutableListOf<Link>()
val substances = mutableListOf<Substance>()
var cellCounter = 0
var genomeStage = 0

var isShowCell = true
var isShowPhysicalLink = true
var isShowNeuronLink = true
//var pikSounds = emptyList<Sound>()
