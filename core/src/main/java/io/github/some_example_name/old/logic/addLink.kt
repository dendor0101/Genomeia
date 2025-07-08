package io.github.some_example_name.old.logic

fun CellManager.addStickyLink(i: Int, j: Int, distance: Float) {
    linksLastId++
    linksLength[linksLastId] = distance
    degreeOfShortening[linksLastId] = 1f
    directedNeuronLink[linksLastId] = -1
    isStickyLink[linksLastId] = true
    isNeuronLink[linksLastId] = false
    links1[linksLastId] = i
    links2[linksLastId] = j
    linkIdMap.put(i, j, linksLastId)
    addLink(i, linksLastId)
    addLink(j, linksLastId)
}
