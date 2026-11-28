package aikayun.muchen.shop

internal object ShellDimensions {
    fun toolbarDp(fontScale: Float) = maxOf(52f, 35f * fontScale)
    fun topBarDp(fontScale: Float) = toolbarDp(fontScale) + 16f
}
