Hey @{report.username}, here's your report for {report.repositoryName} on {report.localDate}.

# Triage
{#include MessageFormatter/notificationBodyBucketContent bucket=report.triage /}

---
<sup>If you no longer want to receive these notifications, send a pull request to the GitHub repository `{report.repositoryName}` to remove the section relative to your username from the file `{github:configPath}`.</sup>
