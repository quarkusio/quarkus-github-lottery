{@io.quarkus.github.lottery.draw.LotteryReport$Bucket bucket}
{#if bucket.issues.isEmpty}
No issues in this category this time.
{#else}
{#for issue in bucket.issues}
 - [#{issue.number}]({issue.url}) {issue.title}
{/for}
{/if}