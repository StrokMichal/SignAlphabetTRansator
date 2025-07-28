package com.google.mediapipe.examples.handlandmarker.fragment
import kotlin.collections.ArrayDeque

class LetterEmitter () {
    lateinit var staticList: ArrayDeque<String>
    lateinit var dynamicList: ArrayDeque<String>

    val letterAssurance = 0.70
    val arrayLength = 8
    fun convertDynamicToMatrix(dynamicLabel: String?): ArrayDeque<String> {
        if (dynamicLabel != null){
            dynamicList.add(dynamicLabel)
            if (dynamicList.count() >= arrayLength)
            {
                dynamicList.removeFirst()
            }
        }
        return dynamicList
    }

    fun convertToStaticMatrix(staticLabel: String?): ArrayDeque<String> {
        if (staticLabel != null){
            staticList.add(staticLabel)
            if (staticList.count() >= arrayLength)
            {
                staticList.removeFirst()
            }
        }
        return staticList
    }
    fun getMostCommonLetter(letterDeque: ArrayDeque<String>): Pair<String?, Double>{

        val counts = letterDeque.groupingBy { it }.eachCount()
        if (counts.contains("G") && counts.contains("UNKNOWN")){
         return Pair("G", 0.9)
        }
        val maxEntry = counts.maxByOrNull { it.value }
        return if (maxEntry!=null){
            val freq = maxEntry.value.toDouble()/letterDeque.size
            Pair(maxEntry.key, freq)
        }else{
            Pair(null, 0.0)
        }
    }

    fun decideWhichModel(mostCommonStatic: Pair<String?, Double>,mostCommonDynamic: Pair<String?, Double>): String? {

        return if (mostCommonStatic.second > letterAssurance
            && mostCommonDynamic.first == "STOP"
            && mostCommonStatic.first != "Z"
            && mostCommonStatic.first != "D"
            && mostCommonStatic.first != "F") {
            mostCommonStatic.first
        } else if(mostCommonDynamic.second > letterAssurance && mostCommonDynamic.first != "STOP"){
            mostCommonDynamic.first
        } else {
            null
        }
    }

    fun getLetterToOutput(commonLetter: String){

    }
}