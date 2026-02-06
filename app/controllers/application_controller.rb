class ApplicationController < ActionController::Base
  allow_browser versions: :modern
  stale_when_importmap_changes

  private

  def current_account
    Current.account ||= if params[:account_id]
      Account.find_by!(slug: params[:account_id])
    elsif session[:account_id]
      Account.find_by(id: session[:account_id])
    end
  end
  helper_method :current_account

  def current_user
    Current.user ||= if session[:user_id] && current_account
      user = User.find_by(id: session[:user_id])
      user if user&.member_of?(current_account)
    end
  end
  helper_method :current_user

  def require_authentication
    unless current_user
      redirect_to new_session_path, alert: "Please sign in"
    end
  end

  def require_account
    current_account
  rescue ActiveRecord::RecordNotFound
    redirect_to root_path, alert: "Account not found"
  end

  def require_manager
    unless current_user&.manager_of?(current_account)
      redirect_to account_root_path(current_account.slug), alert: "Not authorized"
    end
  end

  def require_admin
    unless current_user&.admin_of?(current_account)
      redirect_to account_root_path(current_account.slug), alert: "Not authorized"
    end
  end
end
