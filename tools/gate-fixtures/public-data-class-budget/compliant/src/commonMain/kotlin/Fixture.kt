public data class AllowedData(
    public val value: String,
)

internal class InternalOwner {
    public data class NestedInternalData(
        public val value: String,
    )
}
