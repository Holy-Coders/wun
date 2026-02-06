class AddSystemToRules < ActiveRecord::Migration[8.1]
  def change
    add_column :rules, :system, :boolean, default: false, null: false
  end
end
