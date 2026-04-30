(ns myapp.web.renderers
  "DOM renderers for app-namespace components. The framework's
   `:wun/*` renderers are registered by wun.web.foundation; this is
   where you put bindings for your `:myapp/*` components.

   No registrations yet -- add them as you ship `defcomponent` entries
   in src/myapp/components.cljc. Example:

     (r/register! :myapp/Card
       (fn [{:keys [title]} children]
         (into [:div.myapp-card
                (when title [:h3 title])]
               children)))"
  (:require [wun.web.renderers :as r]))
