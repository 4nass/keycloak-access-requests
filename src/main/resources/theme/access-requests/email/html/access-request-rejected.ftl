<#import "template.ftl" as layout>

<@layout.emailLayout>
${kcSanitize(msg("accessRequestRejectedBodyHtml"))?no_esc}
<p><strong>${kcSanitize(msg("accessRequestResourceLabel"))?no_esc}</strong> ${kcSanitize(request.resourceNameSnapshot())?no_esc}</p>
</@layout.emailLayout>
