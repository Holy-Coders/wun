Rails.application.routes.draw do
  get "up" => "rails/health#show", as: :rails_health_check

  # Auth
  get "sign_in", to: "sessions#new", as: :new_session
  post "sign_in", to: "sessions#create", as: :session
  delete "sign_out", to: "sessions#destroy", as: :destroy_session

  # Account-scoped routes — tenant is always in the URL
  scope "/:account_id", account_id: /[a-z0-9-]+/ do
    resources :cards do
      resources :comments, only: [:create, :destroy]
      resources :taggings, only: [:create, :destroy]
      resources :events, only: [:index]
    end

    resources :tags, only: [:index, :show, :create, :destroy]
    resources :rules

    # Focus actions
    post "cards/:id/activate", to: "cards#activate", as: :activate_card
    post "cards/:id/deactivate", to: "cards#deactivate", as: :deactivate_card
    post "cards/:id/done", to: "cards#done", as: :done_card
    post "cards/:id/move", to: "cards#move", as: :move_card
    post "cards/:id/idle_deactivate", to: "cards#idle_deactivate", as: :idle_deactivate_card

    post "working_late", to: "cards#working_late", as: :working_late

    get "dashboard", to: "dashboard#index", as: :dashboard
    get "dashboard/audit", to: "dashboard#audit", as: :audit_dashboard
    post "dashboard/repair", to: "dashboard#repair", as: :repair_dashboard

    root "cards#index", as: :account_root
  end

  root "landing#index"
end
