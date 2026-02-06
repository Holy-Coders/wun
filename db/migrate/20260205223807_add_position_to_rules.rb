class AddPositionToRules < ActiveRecord::Migration[8.1]
  def change
    add_column :rules, :position, :integer, null: false, default: 0
  end
end
