require "test_helper"

class WorkingHoursPolicyTest < ActiveSupport::TestCase
  setup do
    @config = {
      "timezone" => "Asia/Jerusalem",
      "days" => [0, 1, 2, 3, 4], # Sun-Thu
      "start_hour" => 9,
      "end_hour" => 18
    }
    @policy = WorkingHoursPolicy.new(@config)
  end

  test "timezone returns configured timezone" do
    assert_equal ActiveSupport::TimeZone["Asia/Jerusalem"], @policy.timezone
  end

  test "timezone defaults to UTC when not specified" do
    policy = WorkingHoursPolicy.new({})
    assert_equal ActiveSupport::TimeZone["UTC"], policy.timezone
  end

  test "working_days returns configured days" do
    assert_equal [0, 1, 2, 3, 4], @policy.working_days
  end

  test "start_hour and end_hour return configured values" do
    assert_equal 9, @policy.start_hour
    assert_equal 18, @policy.end_hour
  end

  test "working_time? returns true during working hours on a working day" do
    # Wednesday 12:00 Jerusalem time
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    time = tz.local(2026, 2, 4, 12, 0) # Wed Feb 4 2026
    assert @policy.working_time?(time)
  end

  test "working_time? returns false outside working hours on a working day" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    time = tz.local(2026, 2, 4, 20, 0) # Wed 20:00
    assert_not @policy.working_time?(time)
  end

  test "working_time? returns false before start hour" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    time = tz.local(2026, 2, 4, 7, 0) # Wed 07:00
    assert_not @policy.working_time?(time)
  end

  test "working_time? returns false on non-working day" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    # Friday is wday=5, not in [0,1,2,3,4]
    time = tz.local(2026, 2, 6, 12, 0) # Fri 12:00
    assert_not @policy.working_time?(time)
  end

  test "working_day? returns true for configured days" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    sunday = tz.local(2026, 2, 1, 12, 0) # Sun
    assert @policy.working_day?(sunday)
  end

  test "working_day? returns false for non-configured days" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    saturday = tz.local(2026, 2, 7, 12, 0) # Sat (wday=6)
    assert_not @policy.working_day?(saturday)
  end

  test "boundary_time_for returns end_hour on given date" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    time = tz.local(2026, 2, 4, 10, 30)
    boundary = @policy.boundary_time_for(time)
    assert_equal 18, boundary.hour
    assert_equal 0, boundary.min
  end

  test "start_time_for returns start_hour on given date" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    time = tz.local(2026, 2, 4, 10, 30)
    start = @policy.start_time_for(time)
    assert_equal 9, start.hour
    assert_equal 0, start.min
  end

  test "next_boundary returns today's end_hour when during working hours" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    time = tz.local(2026, 2, 4, 14, 0) # Wed 14:00
    boundary = @policy.next_boundary(time)
    assert_equal tz.local(2026, 2, 4, 18, 0), boundary
  end

  test "next_boundary returns next working day's end_hour when after hours" do
    tz = ActiveSupport::TimeZone["Asia/Jerusalem"]
    time = tz.local(2026, 2, 5, 20, 0) # Thu 20:00, next working day is Sun
    boundary = @policy.next_boundary(time)
    assert_equal 18, boundary.hour
    assert boundary > time
  end
end
