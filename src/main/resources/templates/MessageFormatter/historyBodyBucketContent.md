{@io.quarkus.github.lottery.draw.DrawRef drawRef}
{@io.quarkus.github.lottery.draw.LotteryReport$Bucket$Serialized bucket}
{#if bucket.issueNumbers.isEmpty}
No issues in this category this time.
{#else}
{#for issueNumber in bucket.issueNumbers}
 - {drawRef.repositoryRef.repositoryName}#{issueNumber}
{/for}
{/if}