(ns wun.foundation.components
  "Foundational `:wun/*` component vocabulary, registered via the same
   `defcomponent` API user code uses. There is no privileged path:
   `:wun/Stack` and `:myapp/RichEditor` are indistinguishable to the
   runtime.

   Schemas use Malli-shaped data but are not validated yet -- Malli
   lives on Clojars. Phase 1.D adds validation; until then the schema
   is documentation that travels with the component."
  (:require [wun.components :refer [defcomponent]]))

(defcomponent :wun/Stack
  {:since    1
   :schema   [:map
              [:gap       {:optional true} :int]
              [:padding   {:optional true} :int]
              [:direction {:optional true} [:enum :row :column]]]
   :loading  :inherit
   :fallback :web
   :ios      "WunStack"
   :android  "WunStack"})

(defcomponent :wun/Text
  {:since    1
   :schema   [:map
              [:variant {:optional true} [:enum :h1 :h2 :body]]]
   :loading  :shimmer
   :fallback :web
   :ios      "WunText"
   :android  "WunText"})

(defcomponent :wun/Button
  {:since    1
   :schema   [:map
              [:on-press {:optional true} :wun/intent-ref]]
   :loading  :inherit
   :fallback :web
   :ios      "WunButton"
   :android  "WunButton"})

(defcomponent :wun/WebFrame
  {:since    1
   :schema   [:map
              [:src     {:optional true} :string]
              [:missing {:optional true} :keyword]
              [:reason  {:optional true} :string]]
   :loading  :inherit
   :fallback :none
   :ios      "WunWebFrame"
   :android  "WunWebFrame"})

(defcomponent :wun/Image
  {:since    1
   :schema   [:map
              [:src :string]
              [:alt  {:optional true} :string]
              [:size {:optional true} :int]]
   :loading  :shimmer
   :fallback :web
   :ios      "WunImage"
   :android  "WunImage"})

(defcomponent :wun/Card
  {:since    1
   :schema   [:map
              [:title {:optional true} :string]]
   :loading  :inherit
   :fallback :web
   :ios      "WunCard"
   :android  "WunCard"})

(defcomponent :wun/Avatar
  {:since    1
   :schema   [:map
              [:src      {:optional true} :string]
              [:initials {:optional true} :string]
              [:size     {:optional true} :int]]
   :loading  :shimmer
   :fallback :web
   :ios      "WunAvatar"
   :android  "WunAvatar"})

(defcomponent :wun/Input
  {:since    1
   :schema   [:map
              [:value       {:optional true} :string]
              [:placeholder {:optional true} :string]
              [:on-change   {:optional true} :wun/intent-ref]]
   :loading  :inherit
   :fallback :web
   :ios      "WunInput"
   :android  "WunInput"})

(defcomponent :wun/List
  {:since    1
   :schema   [:map]
   :loading  :inherit
   :fallback :web
   :ios      "WunList"
   :android  "WunList"})

(defcomponent :wun/Spacer
  {:since    1
   :schema   [:map
              [:size {:optional true} :int]]
   :loading  :none
   :fallback :web
   :ios      "WunSpacer"
   :android  "WunSpacer"})

(defcomponent :wun/ScrollView
  {:since    1
   :schema   [:map
              [:direction {:optional true} [:enum :vertical :horizontal]]]
   :loading  :inherit
   :fallback :web
   :ios      "WunScrollView"
   :android  "WunScrollView"})
