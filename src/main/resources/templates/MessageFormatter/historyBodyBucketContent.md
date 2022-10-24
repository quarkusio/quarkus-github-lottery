{#if bucket.issueNumbers.isEmpty}
No issues in this category this time.
{#else}
{#for issueNumber in bucket.issueNumbers}
 - {drawRef.repositoryRef.repositoryName}#{issueNumber}
{/for}
{/if}