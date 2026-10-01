import type { QuickTemplate } from '../api/quickTemplates.ts'

type Props = {
  templates: QuickTemplate[] | null
  busy: boolean
  onApply: (template: QuickTemplate) => void
}

export function QuickTemplateBar({ templates, busy, onApply }: Props) {
  if (templates === null || templates.length === 0) {
    return null
  }

  return (
    <div role="group" aria-label="Quick templates" className="quick-templates">
      {templates.map((template) => (
        <button
          key={template.id}
          type="button"
          disabled={busy || template.category.archived}
          onClick={() => onApply(template)}
        >
          {`${template.name} ${template.amount}${template.category.archived ? ' (category archived)' : ''}`}
        </button>
      ))}
    </div>
  )
}
