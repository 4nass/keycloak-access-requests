<#import "template.ftl" as layout>

<@layout.emailLayout>
${kcSanitize(msg("accessRequestSubmittedBodyHtml"))?no_esc}
<p><strong>${kcSanitize(msg("accessRequestResourceLabel"))?no_esc}</strong> ${kcSanitize(request.resourceNameSnapshot())?no_esc}</p>
<p><strong>${kcSanitize(msg("accessRequestJustificationLabel"))?no_esc}</strong><br/>${kcSanitize(request.justification())?no_esc}</p>
</@layout.emailLayout>
