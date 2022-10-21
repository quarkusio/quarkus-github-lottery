Here are the reports for {drawRef.repositoryName} on {drawRef.instant}.

{#for report in reports}
# {report.username}
## Triage
{#include MessageFormatter/historyBodyBucketContent bucket=report.triage /}

{/for}
{payload}
