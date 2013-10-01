# Caveats:
#   You can't change the options once they're set, you need to define a new test
#
# Example setup in viewModel:
#   @ab = new(CI.ABTests({options: {daniel_test: [true, false]}})).ab_tests
#
# Example usage in view:
#   %p{href: "#", data-bind: "if: ab().daniel_test"}
#     This is the text that will show up if option is set to true
#   %p{href: "#", data-bind: "if: ab().daniel_test"}
#     This is the text that will show up if option is set to false

CI.ABTests = class ABTests
  constructor: (test_definitions, options={}) ->

    @cookie_name = options.cookie_name || "ab_test_user_seed"

    @user_seed = @get_user_seed()

    # defs don't need to be an observable, but we may want to do
    # inference in the future. Better to set it up now.
    @test_definitions = ko.observable(test_definitions)

    # @ab_tests is an object with format {'test_name': "chosen_option", ...}
    # Again, no need to be observable yet
    @ab_tests = ko.observable({})

    @setup_tests()

    @apply_overrides()

    @notify_mixpanel()

  get_user_seed: =>
    if not $.cookie(@cookie_name)?
      @new_cookie = true
      $.cookie(@cookie_name, Math.random(), {expires: 365, path: "/"})
    parseFloat($.cookie(@cookie_name))

  option_index: (seed, name, options) =>
    Math.abs(CryptoJS.MD5("#{seed}#{name}").words[0] % options.length)

  setup_tests: () =>
    tests = {}
    for own name, options of @test_definitions().options

      value = options[@option_index(@user_seed, name, options)]

      tests[name] = ko.observable(value)

      console.log "Set (or reseting) A/B test '#{name}' to value '#{value}'"

    @ab_tests(tests)

  apply_overrides: () =>
    for override in @test_definitions().overrides
      if override.override_p()
        for own name, value of override.options
          if @ab_tests()[name]
            console.log "Overriding A/B test '#{name}' with value '#{value}'"
            @ab_tests()[name](value)

  notify_mixpanel: () =>
    mixpanel.register_once ko.toJS(@ab_tests)

    if @new_cookie
      mixpanel.register
        first_page_load: true
    else
      mixpanel.unregister 'first_page_load'

    if mixpanel.get_property('mp_name_tag')
      mixpanel.register
        existing_user: true
    else
      mixpanel.register
        existing_user: false
