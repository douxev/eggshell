import TransitionCore

// Display labels + default emojis for metric sliders. Built-in gauges are seeded
// with an EMPTY `label` (see migration 0010_metrics.sql) — the app resolves a
// localized name by `metricKey`. Custom sliders carry their own label/emojis.
// Mirrors android MetricSliders.kt (metricLabel / metricEmojis / builtinLabelRes).
enum MetricCatalog {
    /// The label to show: the user's free-text label for custom sliders, or a
    /// French name for built-ins (whose stored label is empty).
    static func displayLabel(_ def: MetricDefinition) -> String {
        if !def.label.isEmpty { return def.label }
        return builtinLabel(domain: def.domain, key: def.metricKey) ?? def.metricKey
    }

    /// The emoji pair: the slider's own emojis, or sensible built-in defaults.
    static func emojis(_ def: MetricDefinition) -> (String?, String?) {
        if def.emojiLeft?.isEmpty == false || def.emojiRight?.isEmpty == false {
            return (def.emojiLeft, def.emojiRight)
        }
        return builtinEmojis(def.metricKey)
    }

    private static func builtinLabel(domain: String, key: String) -> String? {
        switch domain {
        case "journal":
            switch key {
            case "mood":      return "Humeur"
            case "dysphoria": return "Dysphorie"
            case "euphoria":  return "Euphorie"
            case "libido":    return "Libido"
            case "energy":    return "Énergie"
            default:          return nil
            }
        case "bleeding":
            switch key {
            case "flow":      return "Abondance"
            case "pain":      return "Douleur"
            case "cramps":    return "Crampes"
            default:          return nil
            }
        default:
            return nil
        }
    }

    private static func builtinEmojis(_ key: String) -> (String?, String?) {
        switch key {
        case "mood":      return ("😞", "😊")
        case "dysphoria": return ("😌", "😣")
        case "euphoria":  return ("😐", "😄")
        case "libido":    return ("💤", "🔥")
        case "energy":    return ("🥱", "⚡")
        case "flow":      return ("💧", "🩸")
        case "pain":      return ("🙂", "😖")
        case "cramps":    return ("🙂", "😣")
        default:          return (nil, nil)
        }
    }
}
