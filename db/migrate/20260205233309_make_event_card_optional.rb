class MakeEventCardOptional < ActiveRecord::Migration[8.1]
  def change
    change_column_null :events, :card_id, true
    remove_foreign_key :events, :cards
    add_foreign_key :events, :cards, column: :card_id, on_delete: :nullify
  end
end
