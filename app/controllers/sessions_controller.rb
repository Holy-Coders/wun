class SessionsController < ApplicationController
  def new
  end

  def create
    account = Account.find_by(slug: params[:account_slug])

    if account
      user = account.users.find_by(email_address: params[:email_address])

      if user&.authenticate(params[:password]) && user.member_of?(account)
        session[:user_id] = user.id
        session[:account_id] = account.id
        redirect_to account_root_path(account.slug), notice: "Signed in"
        return
      end
    end

    flash.now[:alert] = "Invalid account, email, or password"
    render :new, status: :unprocessable_entity
  end

  def destroy
    reset_session
    redirect_to root_path, notice: "Signed out"
  end
end
