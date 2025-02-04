package xtdb.util

class RowCounter(var blockIdx: Long) {
    var blockRowCount: Long = 0
        private set

    fun nextBlock() {
        blockIdx += 1
        blockRowCount = 0
    }

    fun addRows(rowCount: Int) {
        blockRowCount += rowCount.toLong()
    }
}
