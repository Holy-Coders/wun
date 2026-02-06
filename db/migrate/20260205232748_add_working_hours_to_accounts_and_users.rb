class AddWorkingHoursToAccountsAndUsers < ActiveRecord::Migration[8.1]
  def change
    add_column :accounts, :working_hours, :json
    add_column :memberships, :working_hours_override, :json
    add_column :memberships, :after_hours_until, :datetime
  end
end
