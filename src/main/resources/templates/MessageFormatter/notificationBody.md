Hey @{report.username}, here's your report for {report.repositoryName} on {report.localDate}.

{#let bucket=report.triage}
# Triage
{#if bucket.issues.isEmpty}
No issues in this category this time.
{#else}
{#for issue in bucket.issues}
 - [#{issue.number}]({issue.url}) {issue.title}
{/for}
{/if}
{/let}

---
<sup>If you no longer want to receive these notifications, send a pull request to the GitHub repository `{report.repositoryName}` to remove the section relative to your username from the file `{github:configPath}`.</sup>
